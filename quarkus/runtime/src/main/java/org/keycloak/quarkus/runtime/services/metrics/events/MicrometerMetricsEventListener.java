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
import org.jboss.logging.Logger;
import org.keycloak.events.Details;
import org.keycloak.events.Errors;
import org.keycloak.events.Event;
import org.keycloak.events.EventListenerProvider;
import org.keycloak.events.EventListenerTransaction;
import org.keycloak.events.EventType;
import org.keycloak.events.admin.AdminEvent;
import org.keycloak.events.admin.OperationType;
import org.keycloak.models.KeycloakSession;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Map;
import java.util.stream.Collectors;

public class MicrometerMetricsEventListener implements EventListenerProvider {

    private static final Logger logger = Logger.getLogger(MicrometerMetricsEventListener.class);

    private static final EnumSet<EventType> LOGIN_EVENT_TYPES =
            EnumSet.of(EventType.LOGIN, EventType.LOGIN_ERROR);

    private static final String PROVIDER_KEYCLOAK_OPENID = "keycloak";
    private static final String REALM_TAG = "realm";
    private static final String PROVIDER_TAG = "provider";
    private static final String CLIENT_ID_TAG = "client.id";
    private static final String ERROR_TAG = "error";
    private static final String RESOURCE_TAG = "resource";

    private static final String KEYLOAK_METER_NAME_PREFIX = "keycloak.";
    private static final String EVENT_PREFIX = KEYLOAK_METER_NAME_PREFIX + "event.";
    private static final String LOGIN_COUNTER_NAME = EVENT_PREFIX + "login";
    private static final String LOGIN_ATTEMPT_COUNTER_NAME = EVENT_PREFIX + "login.attempt";
    private static final String LOGIN_ERROR_COUNTER_NAME = EVENT_PREFIX + "login.error";
    private static final String ADMIN_EVENT_PREFIX = KEYLOAK_METER_NAME_PREFIX + "admin.event.";

    static final Map<OperationType, String> ADMIN_OPERATION_TYPE_TO_NAME =
            Arrays.stream(OperationType.values())
                    .collect(Collectors.toMap( o -> o, MicrometerMetricsEventListener::buildCounterName));
    static final Map<EventType, String> EVENT_TYPE_TO_NAME =
            Arrays.stream(EventType.values()).filter(o -> !LOGIN_EVENT_TYPES.contains(o))
                    .collect(Collectors.toMap(e -> e, MicrometerMetricsEventListener::buildCounterName));

    private final EventListenerTransaction tx =
            new EventListenerTransaction(this::countRealmResourceTagsFromGenericAdminEvent, this::countEvent);
    private final MeterRegistry meterRegistry = Metrics.globalRegistry;
    private final EnumSet<EventType> includedEvents;
    private final boolean adminEventEnabled;

    public MicrometerMetricsEventListener(KeycloakSession session, EnumSet<EventType> includedEvents,
                                          boolean adminEventEnabled) {
        session.getTransactionManager().enlistAfterCompletion(tx);
        this.includedEvents = includedEvents;
        this.adminEventEnabled = adminEventEnabled;
    }

    @Override
    public void onEvent(Event event) {
        if (includedEvents.contains(event.getType())) {
            tx.addEvent(event);
        }
    }

    private void countEvent(Event event) {
        logger.debugf("Received user event of type %s in realm %s",
                event.getType().name(), event.getRealmName());
        switch (event.getType()) {
            case LOGIN:
                countLogin(event);
                break;
            case LOGIN_ERROR:
                countLoginError(event);
                break;
            case CLIENT_LOGIN:
            case REGISTER:
            case REFRESH_TOKEN:
            case CODE_TO_TOKEN:
                countRealmProviderClientIdTagsFromEvent(event);
                break;
            case CLIENT_LOGIN_ERROR:
            case REGISTER_ERROR:
            case REFRESH_TOKEN_ERROR:
            case CODE_TO_TOKEN_ERROR:
                countRealmProviderClientIdErrorTagsFromEvent(event);
                break;
            default:
                countRealmTagFromEvent(event);
        }
    }

    @Override
    public void onEvent(AdminEvent event, boolean includeRepresentation) {
        if (adminEventEnabled) {
            tx.addAdminEvent(event, includeRepresentation);
        }
    }

    private void countRealmTagFromEvent(final Event event) {
        meterRegistry.counter(EVENT_TYPE_TO_NAME.get(event.getType()),
                REALM_TAG, nullToEmpty(event.getRealmName())).increment();
    }

    private void countRealmResourceTagsFromGenericAdminEvent(final AdminEvent event, boolean includeRepresentation) {
        logger.debugf("Received admin event of type %s (%s) in realm %s",
                event.getOperationType().name(), event.getResourceType().name(), event.getRealmName());
        meterRegistry.counter(ADMIN_OPERATION_TYPE_TO_NAME.get(event.getOperationType()),
                REALM_TAG, nullToEmpty(event.getRealmName()),
                RESOURCE_TAG, event.getResourceType().name()).increment();
    }

    private void countLogin(final Event event) {
        final String provider = getIdentityProvider(event);
        meterRegistry.counter(LOGIN_ATTEMPT_COUNTER_NAME,
                REALM_TAG, nullToEmpty(event.getRealmName()),
                PROVIDER_TAG, provider,
                CLIENT_ID_TAG, nullToEmpty(event.getClientId())).increment();
        meterRegistry.counter(LOGIN_COUNTER_NAME,
                REALM_TAG, nullToEmpty(event.getRealmName()),
                PROVIDER_TAG, provider,
                CLIENT_ID_TAG, nullToEmpty(event.getClientId())).increment();
    }

    private void countLoginError(final Event event) {
        final String provider = getIdentityProvider(event);
        String clientId = getErrorClientId(event);
        meterRegistry.counter(LOGIN_ATTEMPT_COUNTER_NAME,
                REALM_TAG, nullToEmpty(event.getRealmName()),
                PROVIDER_TAG, provider,
                CLIENT_ID_TAG, clientId).increment();
        meterRegistry.counter(LOGIN_ERROR_COUNTER_NAME,
                REALM_TAG, nullToEmpty(event.getRealmName()),
                PROVIDER_TAG, provider,
                CLIENT_ID_TAG, clientId,
                ERROR_TAG, nullToEmpty(event.getError())).increment();
    }

    private void countRealmProviderClientIdTagsFromEvent(final Event event) {
        meterRegistry.counter(EVENT_TYPE_TO_NAME.get(event.getType()),
                REALM_TAG, nullToEmpty(event.getRealmName()),
                PROVIDER_TAG, getIdentityProvider(event),
                CLIENT_ID_TAG, nullToEmpty(event.getClientId())).increment();
    }

    private void countRealmProviderClientIdErrorTagsFromEvent(final Event event) {
        meterRegistry.counter(EVENT_TYPE_TO_NAME.get(event.getType()),
                REALM_TAG, nullToEmpty(event.getRealmName()),
                PROVIDER_TAG, getIdentityProvider(event),
                CLIENT_ID_TAG, getErrorClientId(event),
                ERROR_TAG, nullToEmpty(event.getError())).increment();
    }

    private String getIdentityProvider(Event event) {
        String identityProvider = null;
        if (event.getDetails() != null) {
            identityProvider = event.getDetails().get(Details.IDENTITY_PROVIDER);
        }
        if (identityProvider == null) {
            identityProvider = PROVIDER_KEYCLOAK_OPENID;
        }
        return identityProvider;
    }

    private String getErrorClientId(Event event) {
        return nullToEmpty(Errors.CLIENT_NOT_FOUND.equals(event.getError())
                ? Errors.CLIENT_NOT_FOUND : event.getClientId());
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String buildCounterName(OperationType type) {
        return ADMIN_EVENT_PREFIX + type.name().toLowerCase().replace("_", ".");
    }

    private static String buildCounterName(EventType type) {
        return EVENT_PREFIX + type.name().toLowerCase().replace("_", ".");
    }

    @Override
    public void close() {
        // unused
    }
}
