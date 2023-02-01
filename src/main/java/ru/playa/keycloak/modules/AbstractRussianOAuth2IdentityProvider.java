package ru.playa.keycloak.modules;

import org.keycloak.OAuth2Constants;
import org.keycloak.broker.oidc.AbstractOAuth2IdentityProvider;
import org.keycloak.broker.oidc.OAuth2IdentityProviderConfig;
import org.keycloak.broker.provider.BrokeredIdentityContext;
import org.keycloak.broker.provider.util.SimpleHttp;
import org.keycloak.common.ClientConnection;
import org.keycloak.events.Errors;
import org.keycloak.events.EventBuilder;
import org.keycloak.events.EventType;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.services.ErrorPage;
import org.keycloak.services.messages.Messages;
import org.keycloak.sessions.AuthenticationSessionModel;

import javax.ws.rs.GET;
import javax.ws.rs.QueryParam;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.Response;

/**
 * Базовый провайдер OAuth-авторизации для российских социальных сетей.
 *
 * @author Anatoliy Pokhresnyi
 */
public abstract class AbstractRussianOAuth2IdentityProvider<C extends OAuth2IdentityProviderConfig>
        extends AbstractOAuth2IdentityProvider<C> {

    /**
     * Создает объект OAuth-авторизации для российских социальных сейтей.
     *
     * @param session Сессия Keycloak.
     * @param config  Конфигурация OAuth-авторизации.
     */
    public AbstractRussianOAuth2IdentityProvider(KeycloakSession session, C config) {
        super(session, config);
    }

    @Override
    public Object callback(RealmModel realm, AuthenticationCallback callback, EventBuilder event) {
        return new Endpoint(callback, event);
    }

    /**
     * Переопределенный класс {@link AbstractOAuth2IdentityProvider.Endpoint}.
     * Класс переопределен с целью возвращения человеко-читаемой ошибки если
     * в профиле социальной сети не указана электронная почта.
     */
    protected class Endpoint {

        private final AuthenticationCallback callback;
        private final EventBuilder event;

        @Context
        private KeycloakSession session;

        @Context
        private ClientConnection clientConnection;

        @Context
        private HttpHeaders headers;

        public Endpoint(AuthenticationCallback aCallback, EventBuilder aEvent) {
            this.callback = aCallback;
            this.event = aEvent;
        }

        @GET
        public Response authResponse(
                @QueryParam(AbstractOAuth2IdentityProvider.OAUTH2_PARAMETER_STATE) String state,
                @QueryParam(AbstractOAuth2IdentityProvider.OAUTH2_PARAMETER_CODE) String authorizationCode,
                @QueryParam(OAuth2Constants.ERROR) String error) {
            logger.infof(
                    "Endpoint AuthResponse. State: %s. Code: %s. Error %s", state, authorizationCode, error
            );

            if (error != null) {
                if (error.equals(ACCESS_DENIED)) {
                    logger.error(ACCESS_DENIED + " for broker login " + getConfig().getProviderId());
                    return callback.cancelled();
                } else {
                    logger.error(error + " for broker login " + getConfig().getProviderId());
                    return callback.error(Messages.IDENTITY_PROVIDER_UNEXPECTED_ERROR);
                }
            }

            try {
                AuthenticationSessionModel authSession = this.callback.getAndVerifyAuthenticationSession(state);
                this.session.getContext().setAuthenticationSession(authSession);

                logger.info("Authentication session is set");

                if (authorizationCode != null) {
                    String response = this.generateTokenRequest(authorizationCode).asString();

                    logger.infof("Get token. Response %s", response);

                    BrokeredIdentityContext federatedIdentity = getFederatedIdentity(response);
                    if (getConfig().isStoreToken() && federatedIdentity.getToken() == null) {
                        federatedIdentity.setToken(response);
                    }

                    federatedIdentity.setIdpConfig(getConfig());
                    federatedIdentity.setIdp(AbstractRussianOAuth2IdentityProvider.this);
                    federatedIdentity.setAuthenticationSession(authSession);

                    return this.callback.authenticated(federatedIdentity);
                }
            } catch (WebApplicationException e) {
                return e.getResponse();
            } catch (IllegalArgumentException e) {
                logger.error("Failed to make identity provider oauth callback illegal argument exception", e);

                event.event(EventType.LOGIN);
                event.error(Errors.IDENTITY_PROVIDER_LOGIN_FAILURE);
                return ErrorPage.error(session, null,
                        Response.Status.BAD_GATEWAY,
                        MessageUtils.EMAIL);
            } catch (SecurityException e) {
                logger.error("Failed the oauth callback to the security identity provider.", e);

                event.event(EventType.LOGIN);
                event.error(Errors.IDENTITY_PROVIDER_LOGIN_FAILURE);
                return ErrorPage.error(session, null,
                        Response.Status.BAD_GATEWAY,
                        MessageUtils.EMAIL_DOMAIN);
            } catch (Exception e) {
                logger.error("Failed to make identity provider oauth callback", e);
            }

            event.event(EventType.LOGIN);
            event.error(Errors.IDENTITY_PROVIDER_LOGIN_FAILURE);
            return ErrorPage.error(
                    session,
                    null,
                    Response.Status.BAD_GATEWAY,
                    Messages.IDENTITY_PROVIDER_UNEXPECTED_ERROR);
        }

        public SimpleHttp generateTokenRequest(String authorizationCode) {
            return SimpleHttp.doPost(getConfig().getTokenUrl(), session)
                    .param(OAUTH2_PARAMETER_CODE, authorizationCode)
                    .param(OAUTH2_PARAMETER_CLIENT_ID, getConfig().getClientId())
                    .param(OAUTH2_PARAMETER_CLIENT_SECRET, getConfig().getClientSecret())
                    .param(OAUTH2_PARAMETER_REDIRECT_URI, session.getContext().getUri().getAbsolutePath().toString())
                    .param(OAUTH2_PARAMETER_GRANT_TYPE, OAUTH2_GRANT_TYPE_AUTHORIZATION_CODE);
        }
    }
}
