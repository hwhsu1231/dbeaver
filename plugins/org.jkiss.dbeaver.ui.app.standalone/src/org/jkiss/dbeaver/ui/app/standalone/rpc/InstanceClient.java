/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2023 DBeaver Corp and others
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

package org.jkiss.dbeaver.ui.app.standalone.rpc;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.net.InetAddress;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.util.Properties;

/**
 * InstanceClient
 */
public class InstanceClient {

    private static final Log log = Log.getLog(InstanceClient.class);

    @Nullable
    public static IInstanceController createClient(@NotNull String location) {
        return createClient(location, false);
    }

    @Nullable
    public static IInstanceController createClient(@NotNull String location, boolean quiet) {
        try {
            File rmiFile = new File(location, ".metadata/" + IInstanceController.RMI_PROP_FILE);
            if (!rmiFile.exists()) {
                return null;
            }
            Properties props = new Properties();
            try (InputStream is = new FileInputStream(rmiFile)) {
                props.load(is);
            }
            String rmiPort = props.getProperty("port");
            Registry registry = LocateRegistry.getRegistry(
                InetAddress.getLoopbackAddress().getHostAddress(),
                Integer.parseInt(rmiPort));
            return (IInstanceController) registry.lookup(IInstanceController.CONTROLLER_ID);
        } catch (Exception e) {
            if (!quiet) {
                log.debug("Error instantiating RMI client: " + e.getMessage());
            }
        }
        return null;
    }

}