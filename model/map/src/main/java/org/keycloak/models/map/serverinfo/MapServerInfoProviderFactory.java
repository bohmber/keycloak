/*
 * Copyright 2021 Red Hat, Inc. and/or its affiliates
 * and other contributors as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.keycloak.models.map.serverinfo;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import org.jboss.logging.Logger;
import org.keycloak.Config;
import org.keycloak.common.Version;
import org.keycloak.common.util.Base64Url;
import org.keycloak.common.util.RandomString;
import org.keycloak.migration.MigrationModel;
import org.keycloak.migration.ModelVersion;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.ServerInfoProvider;
import org.keycloak.models.ServerInfoProviderFactory;
import org.keycloak.models.map.common.AbstractMapProviderFactory;

public class MapServerInfoProviderFactory extends AbstractMapProviderFactory<ServerInfoProvider> implements ServerInfoProviderFactory {

    private static final String RESOURCES_VERSION_SEED = "resourcesVersionSeed";

    @Override
    public void init(Config.Scope config) {
        String seed = config.get(RESOURCES_VERSION_SEED);
        if (seed == null) {
            Logger.getLogger(ServerInfoProviderFactory.class).warnf("It is recommended to set '%s' property in the %s provider config of serverInfo SPI", RESOURCES_VERSION_SEED, PROVIDER_ID);
            //generate random string for this installation
            seed = RandomString.randomCode(10);
        }
        try {
            Version.RESOURCES_VERSION = Base64Url.encode(MessageDigest.getInstance("MD5")
                    .digest((seed + new ModelVersion(Version.VERSION_KEYCLOAK).toString()).getBytes()))
                    .substring(0, 5);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public ServerInfoProvider create(KeycloakSession session) {
        return INSTANCE;
    }

    private static final ServerInfoProvider INSTANCE =  new ServerInfoProvider() {

        private final MigrationModel INSTANCE = new MigrationModel() {
            @Override
                public String getStoredVersion() {
                    return null;
                }
                @Override
                public String getResourcesTag() {
                    throw new UnsupportedOperationException("Not supported.");
                }
                @Override
                public void setStoredVersion(String version) {
                    throw new UnsupportedOperationException("Not supported.");
                }
        };

        @Override
        public MigrationModel getMigrationModel() {
            return INSTANCE;
        }

        @Override
        public void close() {
        }

    };
}
