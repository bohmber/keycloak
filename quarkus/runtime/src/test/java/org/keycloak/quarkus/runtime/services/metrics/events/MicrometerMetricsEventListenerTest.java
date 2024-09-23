/*
 * Copyright 2024 Red Hat, Inc. and/or its affiliates
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

package org.keycloak.quarkus.runtime.services.metrics.events;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.hamcrest.MatcherAssert;
import org.junit.Before;
import org.junit.Test;
import org.keycloak.component.ComponentModel;
import org.keycloak.events.Event;
import org.keycloak.events.EventType;
import org.keycloak.events.admin.AdminEvent;
import org.keycloak.events.admin.OperationType;
import org.keycloak.events.admin.ResourceType;
import org.keycloak.models.ClientProvider;
import org.keycloak.models.ClientScopeProvider;
import org.keycloak.models.GroupProvider;
import org.keycloak.models.IdentityProviderStorageProvider;
import org.keycloak.models.KeyManager;
import org.keycloak.models.KeycloakContext;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.models.KeycloakTransaction;
import org.keycloak.models.KeycloakTransactionManager;
import org.keycloak.models.RealmProvider;
import org.keycloak.models.RoleProvider;
import org.keycloak.models.SingleUseObjectProvider;
import org.keycloak.models.ThemeManager;
import org.keycloak.models.TokenManager;
import org.keycloak.models.UserLoginFailureProvider;
import org.keycloak.models.UserProvider;
import org.keycloak.models.UserSessionProvider;
import org.keycloak.models.utils.KeycloakModelUtils;
import org.keycloak.provider.Provider;
import org.keycloak.provider.ProviderEvent;
import org.keycloak.provider.ProviderEventListener;
import org.keycloak.provider.ProviderFactory;
import org.keycloak.provider.Spi;
import org.keycloak.services.clientpolicy.ClientPolicyManager;
import org.keycloak.sessions.AuthenticationSessionProvider;
import org.keycloak.vault.VaultTranscriber;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Stream;

import static org.hamcrest.CoreMatchers.is;

@SuppressWarnings("unchecked")
public class MicrometerMetricsEventListenerTest {

    private static final String DEFAULT_REALM_NAME = "myrealm";

    private MeterRegistry meterRegistry;
    private KeycloakSessionFactory keycloakSessionFactory;

    @Before
    public void setup() {
        meterRegistry = new SimpleMeterRegistry();
        Metrics.globalRegistry.add(meterRegistry);
        keycloakSessionFactory = createKeycloakSessionFactory();
    }

    @Test
    public void shouldRegisterCountersForAllKeycloakEvents() {

        MatcherAssert.assertThat(
                "All user events registered",
                EventType.values().length - 10,                             // -10 comes from the events that
                is(MicrometerMetricsEventListener.USER_EVENT_TYPE_TO_NAME.size()));       // have their own counters outside the counter map
        MatcherAssert.assertThat(
                "All admin events registered",
                OperationType.values().length,
                is(MicrometerMetricsEventListener.ADMIN_OPERATION_TYPE_TO_NAME.size()));
    }

    @Test
    public void shouldCorrectlyCountLoginAttemptsForSuccessfulAndFailedAttempts() {
        KeycloakModelUtils.runJobInTransaction(keycloakSessionFactory, (KeycloakSession session) -> {
            // with LOGIN event
            final Event login1 = createEvent(EventType.LOGIN, DEFAULT_REALM_NAME, "THE_CLIENT_ID");
            getMicrometerMetricsEventListener(session).onEvent(login1);
        });
        assertMetric("keycloak.login.attempts", 1, "provider", "keycloak", "client.id", "THE_CLIENT_ID", "realm", DEFAULT_REALM_NAME);
        assertMetric("keycloak.logins", 1, "provider", "keycloak", "client.id", "THE_CLIENT_ID", "realm", DEFAULT_REALM_NAME);

        KeycloakModelUtils.runJobInTransaction(keycloakSessionFactory, (KeycloakSession session) -> {
            // with LOGIN_ERROR event
            final Event event2 = createEvent(EventType.LOGIN_ERROR, DEFAULT_REALM_NAME, "THE_CLIENT_ID", "user_not_found");
            getMicrometerMetricsEventListener(session).onEvent(event2);
        });
        assertMetric("keycloak.login.attempts", 2, "provider", "keycloak", "client.id", "THE_CLIENT_ID", "realm", DEFAULT_REALM_NAME);
        assertMetric("keycloak.failed.login.attempts", 1, "provider", "keycloak", "error", "user_not_found", "client.id", "THE_CLIENT_ID", "realm", DEFAULT_REALM_NAME);
    }


    @Test
    public void shouldCorrectlyCountLoginWhenIdentityProviderIsDefined() {

        KeycloakModelUtils.runJobInTransaction(keycloakSessionFactory, (KeycloakSession session) -> {
            final Event login1 = createEvent(EventType.LOGIN, DEFAULT_REALM_NAME, "THE_CLIENT_ID", tuple("identity_provider", "THE_ID_PROVIDER"));
            getMicrometerMetricsEventListener(session).onEvent(login1);
        });
        assertMetric("keycloak.logins", 1, "provider", "THE_ID_PROVIDER", "client.id", "THE_CLIENT_ID", "realm", DEFAULT_REALM_NAME);

        KeycloakModelUtils.runJobInTransaction(keycloakSessionFactory, (KeycloakSession session) -> {
            final Event login2 = createEvent(EventType.LOGIN, DEFAULT_REALM_NAME, "THE_CLIENT_ID", tuple("identity_provider", "THE_ID_PROVIDER"));
            getMicrometerMetricsEventListener(session).onEvent(login2);
        });
        assertMetric("keycloak.logins", 2, "provider", "THE_ID_PROVIDER", "client.id", "THE_CLIENT_ID", "realm", DEFAULT_REALM_NAME);
    }

    @Test
    public void shouldCorrectlyCountLoginWhenIdentityProviderIsNotDefined() {

        KeycloakModelUtils.runJobInTransaction(keycloakSessionFactory, (KeycloakSession session) -> {
            final Event login1 = createEvent(EventType.LOGIN);
            getMicrometerMetricsEventListener(session).onEvent(login1);
        });
        assertMetric("keycloak.logins", 1, "provider", "keycloak", "client.id", "THE_CLIENT_ID", "realm", DEFAULT_REALM_NAME);

        KeycloakModelUtils.runJobInTransaction(keycloakSessionFactory, (KeycloakSession session) -> {
            final Event login2 = createEvent(EventType.LOGIN);
            getMicrometerMetricsEventListener(session).onEvent(login2);
        });
        assertMetric("keycloak.logins", 2, "provider", "keycloak", "client.id", "THE_CLIENT_ID", "realm", DEFAULT_REALM_NAME);
    }

    @Test
    public void shouldCorrectlyCountLoginsFromDifferentProviders() {

        KeycloakModelUtils.runJobInTransaction(keycloakSessionFactory, (KeycloakSession session) -> {
            // with id provider defined
            final Event login1 = createEvent(EventType.LOGIN, DEFAULT_REALM_NAME, "THE_CLIENT_ID", tuple("identity_provider", "THE_ID_PROVIDER"));
            getMicrometerMetricsEventListener(session).onEvent(login1);
        });

        assertMetric("keycloak.logins", 1, "provider", "THE_ID_PROVIDER", "client.id", "THE_CLIENT_ID", "realm", DEFAULT_REALM_NAME);

        KeycloakModelUtils.runJobInTransaction(keycloakSessionFactory, (KeycloakSession session) -> {
            // without id provider defined
            final Event login2 = createEvent(EventType.LOGIN, DEFAULT_REALM_NAME, "THE_CLIENT_ID");
            getMicrometerMetricsEventListener(session).onEvent(login2);
        });

        assertMetric("keycloak.logins", 1, "provider", "keycloak", "client.id", "THE_CLIENT_ID", "realm", DEFAULT_REALM_NAME);
        assertMetric("keycloak.logins", 1, "provider", "THE_ID_PROVIDER", "client.id", "THE_CLIENT_ID", "realm", DEFAULT_REALM_NAME);
    }

    @Test
    public void shouldRecordLoginsPerRealm() {

        KeycloakModelUtils.runJobInTransaction(keycloakSessionFactory, (KeycloakSession session) -> {
            // realm 1
            final Event login1 = createEvent(EventType.LOGIN, DEFAULT_REALM_NAME, "THE_CLIENT_ID", tuple("identity_provider", "THE_ID_PROVIDER"));
            getMicrometerMetricsEventListener(session).onEvent(login1);
        });

        KeycloakModelUtils.runJobInTransaction(keycloakSessionFactory, (KeycloakSession session) -> {
            // realm 2
            final Event login2 = createEvent(EventType.LOGIN, "OTHER_REALM", "THE_CLIENT_ID", tuple("identity_provider", "THE_ID_PROVIDER"));
            getMicrometerMetricsEventListener(session).onEvent(login2);
        });

        assertMetric("keycloak.logins", 1, "provider", "THE_ID_PROVIDER", "client.id", "THE_CLIENT_ID", "realm", DEFAULT_REALM_NAME);
        assertMetric("keycloak.logins", 1, "provider", "THE_ID_PROVIDER", "client.id", "THE_CLIENT_ID", "realm", "OTHER_REALM");
    }

    @Test
    public void shouldCorrectlyCountLoginError() {

        KeycloakModelUtils.runJobInTransaction(keycloakSessionFactory, (KeycloakSession session) -> {
            // with id provider defined
            final Event event1 = createEvent(EventType.LOGIN_ERROR, DEFAULT_REALM_NAME, "THE_CLIENT_ID", "user_not_found", tuple("identity_provider", "THE_ID_PROVIDER"));
            getMicrometerMetricsEventListener(session).onEvent(event1);
        });
        assertMetric("keycloak.failed.login.attempts", 1, "provider", "THE_ID_PROVIDER", "error", "user_not_found", "client.id", "THE_CLIENT_ID", "realm", DEFAULT_REALM_NAME);

        KeycloakModelUtils.runJobInTransaction(keycloakSessionFactory, (KeycloakSession session) -> {
            // without id provider defined
            final Event event2 = createEvent(EventType.LOGIN_ERROR, DEFAULT_REALM_NAME, "THE_CLIENT_ID", "user_not_found");
            getMicrometerMetricsEventListener(session).onEvent(event2);
        });
        assertMetric("keycloak.failed.login.attempts", 1, "provider", "keycloak", "error", "user_not_found", "client.id", "THE_CLIENT_ID", "realm", DEFAULT_REALM_NAME);
        assertMetric("keycloak.failed.login.attempts", 1, "provider", "THE_ID_PROVIDER", "error", "user_not_found", "client.id", "THE_CLIENT_ID", "realm", DEFAULT_REALM_NAME);
    }

    @Test
    public void shouldCorrectlyCountRegister() {
        KeycloakModelUtils.runJobInTransaction(keycloakSessionFactory, (KeycloakSession session) -> {
            // with id provider defined
            final Event event1 = createEvent(EventType.REGISTER, DEFAULT_REALM_NAME, "THE_CLIENT_ID", tuple("identity_provider", "THE_ID_PROVIDER"));
            getMicrometerMetricsEventListener(session).onEvent(event1);
        });
        assertMetric("keycloak.registrations", 1, "provider", "THE_ID_PROVIDER", "client.id", "THE_CLIENT_ID", "realm", DEFAULT_REALM_NAME);

        KeycloakModelUtils.runJobInTransaction(keycloakSessionFactory, (KeycloakSession session) -> {
            // without id provider defined
            final Event event2 = createEvent(EventType.REGISTER, DEFAULT_REALM_NAME, "THE_CLIENT_ID");
            getMicrometerMetricsEventListener(session).onEvent(event2);
        });
        assertMetric("keycloak.registrations", 1, "provider", "keycloak", "client.id", "THE_CLIENT_ID", "realm", DEFAULT_REALM_NAME);
        assertMetric("keycloak.registrations", 1, "provider", "THE_ID_PROVIDER", "client.id", "THE_CLIENT_ID", "realm", DEFAULT_REALM_NAME);
    }

    @Test
    public void shouldCorrectlyCountRefreshTokens() {
        KeycloakModelUtils.runJobInTransaction(keycloakSessionFactory, (KeycloakSession session) -> {
            // with id provider defined
            final Event event1 = createEvent(EventType.REFRESH_TOKEN, DEFAULT_REALM_NAME, "THE_CLIENT_ID", tuple("identity_provider", "THE_ID_PROVIDER"));
            getMicrometerMetricsEventListener(session).onEvent(event1);
        });

        assertMetric("keycloak.refresh.tokens", 1, "provider", "THE_ID_PROVIDER", "client.id", "THE_CLIENT_ID", "realm", DEFAULT_REALM_NAME);

        KeycloakModelUtils.runJobInTransaction(keycloakSessionFactory, (KeycloakSession session) -> {
            // without id provider defined
            final Event event2 = createEvent(EventType.REFRESH_TOKEN, DEFAULT_REALM_NAME, "THE_CLIENT_ID");
            getMicrometerMetricsEventListener(session).onEvent(event2);
        });

        assertMetric("keycloak.refresh.tokens", 1, "provider", "keycloak", "client.id", "THE_CLIENT_ID", "realm", DEFAULT_REALM_NAME);
        assertMetric("keycloak.refresh.tokens", 1, "provider", "THE_ID_PROVIDER", "client.id", "THE_CLIENT_ID", "realm", DEFAULT_REALM_NAME);
    }

    @Test
    public void shouldCorrectlyCountRefreshTokensErrors() {
        KeycloakModelUtils.runJobInTransaction(keycloakSessionFactory, (KeycloakSession session) -> {
            // with id provider defined
            final Event event1 = createEvent(EventType.REFRESH_TOKEN_ERROR, DEFAULT_REALM_NAME, "THE_CLIENT_ID", "user_not_found", tuple("identity_provider", "THE_ID_PROVIDER"));
            getMicrometerMetricsEventListener(session).onEvent(event1);
        });

        assertMetric("keycloak.refresh.tokens.errors", 1, "provider", "THE_ID_PROVIDER", "error", "user_not_found", "client.id", "THE_CLIENT_ID", "realm", DEFAULT_REALM_NAME);

        KeycloakModelUtils.runJobInTransaction(keycloakSessionFactory, (KeycloakSession session) -> {
            // without id provider defined
            final Event event2 = createEvent(EventType.REFRESH_TOKEN_ERROR, DEFAULT_REALM_NAME, "THE_CLIENT_ID", "user_not_found");
            getMicrometerMetricsEventListener(session).onEvent(event2);
        });

        assertMetric("keycloak.refresh.tokens.errors", 1, "provider", "keycloak", "error", "user_not_found", "client.id", "THE_CLIENT_ID", "realm", DEFAULT_REALM_NAME);
        assertMetric("keycloak.refresh.tokens.errors", 1, "provider", "THE_ID_PROVIDER", "error", "user_not_found", "client.id", "THE_CLIENT_ID", "realm", DEFAULT_REALM_NAME);
    }

    @Test
    public void shouldCorrectlyCountClientLogins() {
        KeycloakModelUtils.runJobInTransaction(keycloakSessionFactory, (KeycloakSession session) -> {
            // with id provider defined
            final Event event1 = createEvent(EventType.CLIENT_LOGIN, DEFAULT_REALM_NAME, "THE_CLIENT_ID", tuple("identity_provider", "THE_ID_PROVIDER"));
            getMicrometerMetricsEventListener(session).onEvent(event1);
        });

        assertMetric("keycloak.client.logins", 1, "provider", "THE_ID_PROVIDER", "client.id", "THE_CLIENT_ID", "realm", DEFAULT_REALM_NAME);

        KeycloakModelUtils.runJobInTransaction(keycloakSessionFactory, (KeycloakSession session) -> {
            // without id provider defined
            final Event event2 = createEvent(EventType.CLIENT_LOGIN, DEFAULT_REALM_NAME, "THE_CLIENT_ID");
            getMicrometerMetricsEventListener(session).onEvent(event2);
        });

        assertMetric("keycloak.client.logins", 1, "provider", "keycloak", "client.id", "THE_CLIENT_ID", "realm", DEFAULT_REALM_NAME);
        assertMetric("keycloak.client.logins", 1, "provider", "THE_ID_PROVIDER", "client.id", "THE_CLIENT_ID", "realm", DEFAULT_REALM_NAME);
    }

    @Test
    public void shouldCorrectlyCountClientLoginAttempts() {

        KeycloakModelUtils.runJobInTransaction(keycloakSessionFactory, (KeycloakSession session) -> {
            // with id provider defined
            final Event event1 = createEvent(EventType.CLIENT_LOGIN_ERROR, DEFAULT_REALM_NAME, "THE_CLIENT_ID", "user_not_found", tuple("identity_provider", "THE_ID_PROVIDER"));
            getMicrometerMetricsEventListener(session).onEvent(event1);
        });
        assertMetric("keycloak.failed.client.login.attempts", 1, "provider", "THE_ID_PROVIDER", "error", "user_not_found", "client.id", "THE_CLIENT_ID", "realm", DEFAULT_REALM_NAME);

        KeycloakModelUtils.runJobInTransaction(keycloakSessionFactory, (KeycloakSession session) -> {
            // without id provider defined
            final Event event2 = createEvent(EventType.CLIENT_LOGIN_ERROR, DEFAULT_REALM_NAME, "THE_CLIENT_ID", "user_not_found");
            getMicrometerMetricsEventListener(session).onEvent(event2);
        });
        assertMetric("keycloak.failed.client.login.attempts", 1, "provider", "keycloak", "error", "user_not_found", "client.id", "THE_CLIENT_ID", "realm", DEFAULT_REALM_NAME);
        assertMetric("keycloak.failed.client.login.attempts", 1, "provider", "THE_ID_PROVIDER", "error", "user_not_found", "client.id", "THE_CLIENT_ID", "realm", DEFAULT_REALM_NAME);
    }

    @Test
    public void shouldCorrectlyCountCodeToTokens() {

        KeycloakModelUtils.runJobInTransaction(keycloakSessionFactory, (KeycloakSession session) -> {
            // with id provider defined
            final Event event1 = createEvent(EventType.CODE_TO_TOKEN, DEFAULT_REALM_NAME, "THE_CLIENT_ID", tuple("identity_provider", "THE_ID_PROVIDER"));
            getMicrometerMetricsEventListener(session).onEvent(event1);
        });
        assertMetric("keycloak.code.to.tokens", 1, "provider", "THE_ID_PROVIDER", "client.id", "THE_CLIENT_ID", "realm", DEFAULT_REALM_NAME);
        KeycloakModelUtils.runJobInTransaction(keycloakSessionFactory, (KeycloakSession session) -> {
            // without id provider defined
            final Event event2 = createEvent(EventType.CODE_TO_TOKEN, DEFAULT_REALM_NAME, "THE_CLIENT_ID");
            getMicrometerMetricsEventListener(session).onEvent(event2);
        });
        assertMetric("keycloak.code.to.tokens", 1, "provider", "keycloak", "client.id", "THE_CLIENT_ID", "realm", DEFAULT_REALM_NAME);
        assertMetric("keycloak.code.to.tokens", 1, "provider", "THE_ID_PROVIDER", "client.id", "THE_CLIENT_ID", "realm", DEFAULT_REALM_NAME);
    }

    @Test
    public void shouldCorrectlyCountCodeToTokensErrors() {

        KeycloakModelUtils.runJobInTransaction(keycloakSessionFactory, (KeycloakSession session) -> {
            // with id provider defined
            final Event event1 = createEvent(EventType.CODE_TO_TOKEN_ERROR, DEFAULT_REALM_NAME, "THE_CLIENT_ID", "user_not_found", tuple("identity_provider", "THE_ID_PROVIDER"));
            getMicrometerMetricsEventListener(session).onEvent(event1);
        });
        assertMetric("keycloak.code.to.tokens.errors", 1, "provider", "THE_ID_PROVIDER", "error", "user_not_found", "client.id", "THE_CLIENT_ID", "realm", DEFAULT_REALM_NAME);

        KeycloakModelUtils.runJobInTransaction(keycloakSessionFactory, (KeycloakSession session) -> {
            // without id provider defined
            final Event event2 = createEvent(EventType.CODE_TO_TOKEN_ERROR, DEFAULT_REALM_NAME, "THE_CLIENT_ID", "user_not_found");
            getMicrometerMetricsEventListener(session).onEvent(event2);
        });
        assertMetric("keycloak.code.to.tokens.errors", 1, "provider", "keycloak", "error", "user_not_found", "client.id", "THE_CLIENT_ID", "realm", DEFAULT_REALM_NAME);
        assertMetric("keycloak.code.to.tokens.errors", 1, "provider", "THE_ID_PROVIDER", "error", "user_not_found", "client.id", "THE_CLIENT_ID", "realm", DEFAULT_REALM_NAME);
    }

    @Test
    public void shouldCorrectlyRecordGenericEvents() {
        final Event event1 = createEvent(EventType.UPDATE_EMAIL);
        KeycloakModelUtils.runJobInTransaction(keycloakSessionFactory, (KeycloakSession session) -> {
            getMicrometerMetricsEventListener(session).onEvent(event1);
        });
        assertMetric("keycloak.user.event.update.email", 1, "realm", DEFAULT_REALM_NAME);
        KeycloakModelUtils.runJobInTransaction(keycloakSessionFactory, (KeycloakSession session) -> {
            getMicrometerMetricsEventListener(session).onEvent(event1);
        });
        assertMetric("keycloak.user.event.update.email", 2, "realm", DEFAULT_REALM_NAME);


        final Event event2 = createEvent(EventType.REVOKE_GRANT);
        KeycloakModelUtils.runJobInTransaction(keycloakSessionFactory, (KeycloakSession session) -> {
            getMicrometerMetricsEventListener(session).onEvent(event2);
        });
        assertMetric("keycloak.user.event.revoke.grant", 1, "realm", DEFAULT_REALM_NAME);
        assertMetric("keycloak.user.event.update.email", 2, "realm", DEFAULT_REALM_NAME);
    }

    @Test
    public void shouldCorrectlyRecordGenericAdminEvents() {

        final AdminEvent event1 = new AdminEvent();
        event1.setOperationType(OperationType.ACTION);
        event1.setResourceType(ResourceType.AUTHORIZATION_SCOPE);
        event1.setRealmName(DEFAULT_REALM_NAME);
        KeycloakModelUtils.runJobInTransaction(keycloakSessionFactory, (KeycloakSession session) -> {

            getMicrometerMetricsEventListener(session).onEvent(event1, false);
        });
        assertMetric("keycloak.admin.event.action", 1, "resource", "AUTHORIZATION_SCOPE", "realm", DEFAULT_REALM_NAME);
        KeycloakModelUtils.runJobInTransaction(keycloakSessionFactory, (KeycloakSession session) -> {
            getMicrometerMetricsEventListener(session).onEvent(event1, false);
        });
        assertMetric("keycloak.admin.event.action", 2, "resource", "AUTHORIZATION_SCOPE", "realm", DEFAULT_REALM_NAME);

        KeycloakModelUtils.runJobInTransaction(keycloakSessionFactory, (KeycloakSession session) -> {
            final AdminEvent event2 = new AdminEvent();
            event2.setOperationType(OperationType.UPDATE);
            event2.setResourceType(ResourceType.CLIENT);
            event2.setRealmName(DEFAULT_REALM_NAME);
            getMicrometerMetricsEventListener(session).onEvent(event2, false);
        });
        assertMetric("keycloak.admin.event.update", 1, "resource", "CLIENT", "realm", DEFAULT_REALM_NAME);
        assertMetric("keycloak.admin.event.action", 2, "resource", "AUTHORIZATION_SCOPE", "realm", DEFAULT_REALM_NAME);
    }

    @Test
    public void shouldTolerateNullLabels() {
        final Event nullEvent = new Event();
        nullEvent.setType(EventType.LOGIN_ERROR);
        nullEvent.setClientId(null);
        nullEvent.setError(null);
        nullEvent.setRealmId(null);

        KeycloakModelUtils.runJobInTransaction(keycloakSessionFactory, (KeycloakSession session) -> {

            getMicrometerMetricsEventListener(session).onEvent(nullEvent);
        });
        assertMetric("keycloak.failed.login.attempts", 1, "provider", "keycloak", "error", "", "client.id", "", "realm", "");
    }

    private void assertGenericMetric(String metricName, double metricValue, String... tags) {
        MatcherAssert.assertThat("Metric value match", meterRegistry.counter(metricName, tags).count() == metricValue);
    }

    private void assertMetric(String metricName, double metricValue, String... tags) {
        this.assertGenericMetric(metricName, metricValue, tags);
    }

    private Event createEvent(EventType type, String realmName, String clientId, String
            error, Tuple<String, String>... tuples) {
        final Event event = new Event();
        event.setType(type);
        event.setRealmName(realmName);
        event.setClientId(clientId);
        if (tuples != null) {
            event.setDetails(new HashMap<>());
            for (Tuple<String, String> tuple : tuples) {
                event.getDetails().put(tuple.left, tuple.right);
            }
        } else {
            event.setDetails(Collections.emptyMap());
        }

        if (error != null) {
            event.setError(error);
        }
        return event;
    }

    private Event createEvent(EventType type, String realmName, String
            clientId, Tuple<String, String>... tuples) {
        return this.createEvent(type, realmName, clientId, null, tuples);
    }

    private Event createEvent(EventType type) {
        return createEvent(type, DEFAULT_REALM_NAME, "THE_CLIENT_ID", (String) null);
    }

    private static MicrometerMetricsEventListener getMicrometerMetricsEventListener(KeycloakSession session) {
        return session.getProvider(MicrometerMetricsEventListener.class);
    }

    private KeycloakSessionFactory createKeycloakSessionFactory() {
        return new KeycloakSessionFactory() {
            @Override
            public KeycloakSession create() {
                return new KeycloakSession() {
                    private final KeycloakTransactionManager transactionManager = createKeycloakTransactionManager();

                    @Override
                    public KeycloakContext getContext() {
                        return null;
                    }

                    @Override
                    public KeycloakTransactionManager getTransactionManager() {
                        return transactionManager;
                    }

                    private KeycloakTransactionManager createKeycloakTransactionManager() {
                        return new KeycloakTransactionManager() {
                            private final List<KeycloakTransaction> transactions = new ArrayList<>();

                            @Override
                            public JTAPolicy getJTAPolicy() {
                                return null;
                            }

                            @Override
                            public void setJTAPolicy(JTAPolicy policy) {

                            }

                            @Override
                            public void enlist(KeycloakTransaction transaction) {

                            }

                            @Override
                            public void enlistAfterCompletion(KeycloakTransaction transaction) {
                                transactions.add(transaction);
                                transaction.begin();
                            }

                            @Override
                            public void enlistPrepare(KeycloakTransaction transaction) {

                            }

                            @Override
                            public void begin() {
                                transactions.forEach(KeycloakTransaction::begin);
                            }

                            @Override
                            public void commit() {
                                transactions.forEach(KeycloakTransaction::commit);
                            }

                            @Override
                            public void rollback() {

                            }

                            @Override
                            public void setRollbackOnly() {

                            }

                            @Override
                            public boolean getRollbackOnly() {
                                return false;
                            }

                            @Override
                            public boolean isActive() {
                                return false;
                            }
                        };
                    }

                    @Override
                    public <T extends Provider> T getProvider(Class<T> clazz) {
                        return (T) new MicrometerMetricsEventListener(
                                this, EnumSet.allOf(EventType.class), true);
                    }

                    @Override
                    public <T extends Provider> T getProvider(Class<T> clazz, String id) {
                        return getProvider(clazz);
                    }

                    @Override
                    public <T extends Provider> T getComponentProvider(Class<T> clazz, String componentId) {
                        return null;
                    }

                    @Override
                    public <T extends Provider> T getComponentProvider(Class<T> clazz, String componentId, Function<KeycloakSessionFactory, ComponentModel> modelGetter) {
                        return null;
                    }

                    @Override
                    public <T extends Provider> T getProvider(Class<T> clazz, ComponentModel componentModel) {
                        return null;
                    }

                    @Override
                    public <T extends Provider> Set<String> listProviderIds(Class<T> clazz) {
                        return Set.of();
                    }

                    @Override
                    public <T extends Provider> Set<T> getAllProviders(Class<T> clazz) {
                        return Set.of();
                    }

                    @Override
                    public Class<? extends Provider> getProviderClass(String providerClassName) {
                        return null;
                    }

                    @Override
                    public Object getAttribute(String attribute) {
                        return null;
                    }

                    @Override
                    public <T> T getAttribute(String attribute, Class<T> clazz) {
                        return null;
                    }


                    @Override
                    public Object removeAttribute(String attribute) {
                        return null;
                    }

                    @Override
                    public void setAttribute(String name, Object value) {

                    }

                    @Override
                    public Map<String, Object> getAttributes() {
                        return Map.of();
                    }

                    @Override
                    public void invalidate(InvalidableObjectType type, Object... params) {

                    }

                    @Override
                    public void enlistForClose(Provider provider) {

                    }

                    @Override
                    public KeycloakSessionFactory getKeycloakSessionFactory() {
                        return keycloakSessionFactory;
                    }

                    @Override
                    public RealmProvider realms() {
                        return null;
                    }

                    @Override
                    public ClientProvider clients() {
                        return null;
                    }

                    @Override
                    public ClientScopeProvider clientScopes() {
                        return null;
                    }

                    @Override
                    public GroupProvider groups() {
                        return null;
                    }

                    @Override
                    public RoleProvider roles() {
                        return null;
                    }

                    @Override
                    public UserSessionProvider sessions() {
                        return null;
                    }

                    @Override
                    public UserLoginFailureProvider loginFailures() {
                        return null;
                    }

                    @Override
                    public AuthenticationSessionProvider authenticationSessions() {
                        return null;
                    }

                    @Override
                    public SingleUseObjectProvider singleUseObjects() {
                        return null;
                    }

                    @Override
                    public IdentityProviderStorageProvider identityProviders() {
                        return null;
                    }

                    @Override
                    public void close() {
                        transactionManager.commit();
                    }

                    @Override
                    public UserProvider users() {
                        return null;
                    }

                    @Override
                    public KeyManager keys() {
                        return null;
                    }

                    @Override
                    public ThemeManager theme() {
                        return null;
                    }

                    @Override
                    public TokenManager tokens() {
                        return null;
                    }

                    @Override
                    public VaultTranscriber vault() {
                        return null;
                    }

                    @Override
                    public ClientPolicyManager clientPolicy() {
                        return null;
                    }

                    @Override
                    public boolean isClosed() {
                        return false;
                    }
                };
            }

            @Override
            public Set<Spi> getSpis() {
                return Set.of();
            }

            @Override
            public Spi getSpi(Class<? extends Provider> providerClass) {
                return null;
            }

            @Override
            public <T extends Provider> ProviderFactory<T> getProviderFactory(Class<T> clazz) {
                return null;
            }

            @Override
            public <T extends Provider> ProviderFactory<T> getProviderFactory(Class<T> clazz, String id) {
                return getProviderFactory(clazz);
            }

            @Override
            public <T extends Provider> ProviderFactory<T> getProviderFactory(Class<T> clazz, String realmId, String componentId, Function<KeycloakSessionFactory, ComponentModel> modelGetter) {
                return getProviderFactory(clazz);
            }

            @Override
            public Stream<ProviderFactory> getProviderFactoriesStream(Class<? extends Provider> clazz) {
                return Stream.empty();
            }

            @Override
            public long getServerStartupTimestamp() {
                return 0;
            }

            @Override
            public void close() {

            }

            @Override
            public void invalidate(KeycloakSession session, InvalidableObjectType type, Object... params) {

            }

            @Override
            public void register(ProviderEventListener listener) {

            }

            @Override
            public void unregister(ProviderEventListener listener) {

            }

            @Override
            public void publish(ProviderEvent event) {

            }
        };
    }


    private static <L, R> Tuple<L, R> tuple(L left, R right) {
        return new Tuple<>(left, right);
    }

    private static final class Tuple<L, R> {
        final L left;
        final R right;

        private Tuple(L left, R right) {
            this.left = left;
            this.right = right;
        }
    }

}
