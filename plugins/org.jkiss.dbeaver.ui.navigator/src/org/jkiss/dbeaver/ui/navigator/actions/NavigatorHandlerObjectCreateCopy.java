/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2025 DBeaver Corp and others
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jkiss.dbeaver.ui.navigator.actions;

import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.resources.IContainer;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.Path;
import org.eclipse.core.runtime.QualifiedName;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.osgi.util.NLS;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.FileTransfer;
import org.eclipse.swt.widgets.*;
import org.eclipse.ui.handlers.HandlerUtil;
import org.jkiss.code.NotNull;
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.model.app.DBPProject;
import org.jkiss.dbeaver.model.navigator.DBNDatabaseNode;
import org.jkiss.dbeaver.model.navigator.DBNNode;
import org.jkiss.dbeaver.model.navigator.DBNResource;
import org.jkiss.dbeaver.model.navigator.fs.DBNPathBase;
import org.jkiss.dbeaver.model.rm.RMConstants;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;
import org.jkiss.dbeaver.runtime.DBWorkbench;
import org.jkiss.dbeaver.ui.UIUtils;
import org.jkiss.dbeaver.ui.dnd.TreeNodeTransfer;
import org.jkiss.dbeaver.ui.internal.UINavigatorMessages;
import org.jkiss.dbeaver.ui.navigator.NavigatorUtils;
import org.jkiss.dbeaver.utils.RuntimeUtils;
import org.jkiss.utils.CommonUtils;

import java.io.*;
import java.lang.reflect.InvocationTargetException;
import java.util.Collection;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

public class NavigatorHandlerObjectCreateCopy extends NavigatorHandlerObjectCreateBase {

    static final Log log = Log.getLog(NavigatorHandlerObjectCreateCopy.class);

    @Override
    public Object execute(ExecutionEvent event) throws ExecutionException {
        Shell activeShell = HandlerUtil.getActiveShell(event);
        Control focusControl = activeShell.getDisplay().getFocusControl();
        if (focusControl instanceof Text) {
            ((Text) focusControl).paste();
            return null;
        } else if (focusControl instanceof StyledText) {
            ((StyledText) focusControl).paste();
            return null;
        } else if (focusControl instanceof Combo) {
            ((Combo) focusControl).paste();
            return null;
        }
        final ISelection selection = HandlerUtil.getCurrentSelection(event);

        DBNNode curNode = NavigatorUtils.getSelectedNode(selection);
        if (curNode == null) {
            return null;
        }
        DBPProject toProject = curNode.getOwnerProject();
        Clipboard clipboard = new Clipboard(Display.getDefault());
        List<String> failedToPasteResources = new LinkedList<>();
        try {
            @SuppressWarnings("unchecked")
            Collection<DBNNode> cbNodes = (Collection<DBNNode>) clipboard.getContents(TreeNodeTransfer.getInstance());
            if (cbNodes != null) {
                for (DBNNode nodeObject : cbNodes) {
                    if (nodeObject instanceof DBNResource && curNode instanceof DBNResource) {
                        if (!toProject.hasRealmPermission(RMConstants.PERMISSION_PROJECT_RESOURCE_EDIT)) {
                            failedToPasteResources.add(nodeObject.getName());
                        }
                    }
                }
                if (failedToPasteResources.isEmpty()) {
                    if (curNode instanceof DBNPathBase pathTarget) {
                        try {
                            UIUtils.runWithMonitor(monitor -> {
                                pathTarget.dropNodes(monitor, cbNodes);
                                return null;
                            });
                        } catch (DBException e) {
                            DBWorkbench.getPlatformUI().showError("Paste error", "Can't paste nodes", e);
                            failedToPasteResources.addAll(cbNodes.stream().map(DBNNode::getNodeDisplayName).toList());
                        }
                    } else {
                        for (DBNNode nodeObject : cbNodes) {
                            if (curNode instanceof DBNResource && ((DBNResource) curNode).supportsPaste(nodeObject)) {
                                try {
                                    ((DBNResource) curNode).pasteNodes(List.of(nodeObject));
                                } catch (DBException e) {
                                    DBWorkbench.getPlatformUI().showError("Paste error", "Can't paste node '" + nodeObject.getName() + "'", e);
                                    failedToPasteResources.add(nodeObject.getName());
                                }
                            } else if (nodeObject instanceof DBNDatabaseNode) {
                                createNewObject(HandlerUtil.getActiveWorkbenchWindow(event), curNode, ((DBNDatabaseNode) nodeObject));
                            } else if (nodeObject instanceof DBNResource && curNode instanceof DBNResource) {
                                pasteResource((DBNResource) nodeObject, (DBNResource) curNode);
                            } else {
                                log.error("Paste is not supported for " + curNode);
                            }
                        }
                    }
                }
            } else if (curNode instanceof DBNResource) {
                String[] files = (String[]) clipboard.getContents(FileTransfer.getInstance());
                if (files != null) {
                    for (String fileName : files) {
                        final File file = new File(fileName);
                        if (file.exists()) {
                            if (!toProject.hasRealmPermission(RMConstants.PERMISSION_PROJECT_RESOURCE_EDIT)) {
                                failedToPasteResources.add(fileName);
                            }
                        }
                    }
                    if (failedToPasteResources.isEmpty()) {
                        for (String fileName : files) {
                            final File file = new File(fileName);
                            if (file.exists()) {
                                pasteResource(file, (DBNResource) curNode);
                            }
                        }
                    }
                } else {
                    log.debug("Paste error: unsupported clipboard format. File or folder were expected.");
                    Display.getCurrent().beep();
                }
            } else {
                log.debug("Paste error: clipboard contains data in unsupported format");
                Display.getCurrent().beep();
            }
            if (!failedToPasteResources.isEmpty()) {
                DBWorkbench.getPlatformUI().showError(
                    UINavigatorMessages.failed_to_paste_due_to_permissions_title,
                    NLS.bind(
                        UINavigatorMessages.failed_to_paste_due_to_permissions_message,
                        toProject.getDisplayName(),
                        String.join(",\n", failedToPasteResources)
                    )
                );
            }
        } finally {
            clipboard.dispose();
        }

        return null;
    }

    private void pasteResource(DBNResource resourceNode, DBNResource toFolder) {
        final IResource resource = resourceNode.getResource();
        final IResource targetResource = toFolder.getResource();
        final IContainer targetFolder = targetResource instanceof IContainer ? (IContainer) targetResource : targetResource.getParent();
        try {
            UIUtils.runInProgressService(monitor -> {
                try {
                    copyResource(monitor, resource, targetFolder);
                } catch (Exception e) {
                    throw new InvocationTargetException(e);
                }
            });
        } catch (InvocationTargetException e) {
            DBWorkbench.getPlatformUI().showError("Copy error", "Error copying resource", e.getTargetException());
        } catch (InterruptedException e) {
            // ignore
        }
    }

    private void copyResource(
        @NotNull DBRProgressMonitor monitor,
        @NotNull IResource resource,
        @NotNull IContainer targetFolder
    ) throws CoreException, IOException {
        final IProgressMonitor nestedMonitor = RuntimeUtils.getNestedMonitor(monitor);
        final String extension = resource.getFileExtension();
        String targetName = resource.getName();

        if (resource.getParent().equals(targetFolder)) {
            String plainName = extension != null && !extension.isEmpty() && targetName.endsWith(extension) ?
                targetName.substring(0, targetName.length() - extension.length() - 1) : targetName;
            for (int i = 1; ; i++) {
                String testName =  plainName + "-" + i;
                if (!CommonUtils.isEmpty(extension)) {
                    testName += "." + extension;
                }
                if (targetFolder.findMember(testName) == null) {
                    targetName = testName;
                    break;
                }
            }
        } else if (targetFolder.findMember(targetName) != null) {
            throw new IOException("Target resource '" + targetName + "' already exists");
        }
        if (resource instanceof IFile) {
            if (targetFolder instanceof IFolder && !targetFolder.exists()) {
                ((IFolder) targetFolder).create(true, true, nestedMonitor);
            }

            // Copy single file
            final IFile targetFile = targetFolder.getFile(new Path(targetName));
            if (!targetFile.exists()) {
                targetFile.create(new ByteArrayInputStream(new byte[0]), true, nestedMonitor);
            }
            final Map<QualifiedName, String> props = resource.getPersistentProperties();
            if (props != null && !props.isEmpty()) {
                for (Map.Entry<QualifiedName, String> prop : props.entrySet()) {
                    targetFile.setPersistentProperty(prop.getKey(), prop.getValue());
                }
            }
            try (InputStream is = ((IFile) resource).getContents()) {
                targetFile.setContents(is, true, true, nestedMonitor);
            }
        } else if (resource instanceof IFolder) {
            // Copy folder with all files and subfolders
        }
    }

    private void pasteResource(final File file, DBNResource toFolder) {
        IResource targetResource = toFolder.getResource();
        IContainer targetFolder = targetResource instanceof IContainer container ? container : targetResource.getParent();
        try {
            UIUtils.runInProgressService(monitor -> {
                try {
                    copyFileInFolder(monitor, targetFolder, file);
                } catch (Exception e) {
                    throw new InvocationTargetException(e);
                }
            });
        } catch (InvocationTargetException e) {
            DBWorkbench.getPlatformUI().showError("Copy error", "Error copying resource", e.getTargetException());
        } catch (InterruptedException e) {
            // ignore
        }
    }

    private void copyFileInFolder(DBRProgressMonitor monitor, IContainer targetFolder, File file) throws IOException, CoreException {
        if (monitor.isCanceled()) {
            return;
        }
        if (file.isDirectory()) {
            IFolder subFolder = targetFolder.getFolder(new Path(file.getName()));
            if (!subFolder.exists()) {
                subFolder.create(true, true, monitor.getNestedMonitor());
            }
            File[] folderFile = file.listFiles();
            if (folderFile != null) {
                for (File subFile : folderFile) {
                    copyFileInFolder(monitor, subFolder, subFile);
                }
            }
        } else {
            final IFile targetFile = targetFolder.getFile(new Path(file.getName()));
            if (targetFile.exists()) {
                throw new IOException("Target file '" + targetFile.getFullPath() + "' already exists");
            }
            try (InputStream is = new FileInputStream(file)) {
                targetFile.create(is, true, monitor.getNestedMonitor());
            }
        }
    }

}