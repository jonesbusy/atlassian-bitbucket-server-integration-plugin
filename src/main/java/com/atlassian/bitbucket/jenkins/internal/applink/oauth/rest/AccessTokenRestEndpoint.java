package com.atlassian.bitbucket.jenkins.internal.applink.oauth.rest;

import com.atlassian.bitbucket.jenkins.internal.applink.oauth.adaptor.OAuthConverter;
import com.atlassian.bitbucket.jenkins.internal.applink.oauth.provider.*;
import com.atlassian.bitbucket.jenkins.internal.provider.JenkinsProvider;
import hudson.Extension;
import net.oauth.OAuthMessage;
import net.oauth.OAuthProblemException;
import net.oauth.OAuthValidator;
import net.oauth.server.OAuthServlet;
import org.kohsuke.stapler.WebMethod;
import org.kohsuke.stapler.interceptor.RequirePOST;

import javax.inject.Inject;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.OutputStream;
import java.time.Clock;
import java.util.logging.Logger;

import static com.atlassian.bitbucket.jenkins.internal.applink.oauth.util.OAuthProblemUtils.logOAuthProblem;
import static net.oauth.OAuth.*;
import static net.oauth.OAuth.Problems.*;
import static net.oauth.server.OAuthServlet.handleException;

@Extension
public class AccessTokenRestEndpoint extends AbstractAPIActionHandler {

    public static final String OAUTH_SESSION_HANDLE = "oauth_session_handle";
    public static final String OAUTH_EXPIRES_IN = "oauth_expires_in";
    public static final String OAUTH_AUTHORIZATION_EXPIRES_IN = "oauth_authorization_expires_in";
    private static final Logger LOGGER = Logger.getLogger(RequestTokenRestEndpoint.class.getName());

    @Inject
    private OAuthValidator oAuthValidator;
    @Inject
    private TokenFactory tokenFactory;
    @Inject
    private ServiceProviderTokenStore tokenStore;
    @Inject
    private Clock clock;

    public AccessTokenRestEndpoint() {
    }

    public AccessTokenRestEndpoint(OAuthValidator oAuthValidator,
                                   JenkinsProvider jenkinsProvider,
                                   TokenFactory tokenFactory,
                                   ServiceProviderTokenStore tokenStore,
                                   Clock clock) {
        super(jenkinsProvider);
        this.oAuthValidator = oAuthValidator;
        this.tokenFactory = tokenFactory;
        this.tokenStore = tokenStore;
        this.clock = clock;
    }

    @RequirePOST
    @WebMethod(name = "access-token")
    public void handleAccessToken(HttpServletRequest request,
                                  HttpServletResponse response) throws ServletException, IOException {
        ServiceProviderToken accessToken;
        try {
            OAuthMessage requestMessage = OAuthServlet.getMessage(request, null);
            requestMessage.requireParameters(OAUTH_TOKEN);
            ServiceProviderToken token;
            try {
                token = tokenStore.get(requestMessage.getToken());
            } catch (InvalidTokenException e) {
                throw new OAuthProblemException(TOKEN_REJECTED);
            }
            if (token == null) {
                throw new OAuthProblemException(TOKEN_REJECTED);
            }
            if (token.isRequestToken()) {
                checkRequestToken(requestMessage, token);
            } else {
                checkAccessToken(requestMessage, token);
            }

            try {
                oAuthValidator.validateMessage(requestMessage, OAuthConverter.asOAuthAccessor(token));
            } catch (OAuthProblemException ope) {
                logOAuthProblem(requestMessage, ope, LOGGER);
                throw ope;
            }

            accessToken = tokenStore.put(tokenFactory.generateAccessToken(token));
            tokenStore.remove(token.getToken());
        } catch (Exception e) {
            handleException(response, e, getBaseUrl(), true);
            return;
        }

        response.setContentType("text/plain");
        OutputStream out = response.getOutputStream();
        formEncode(newList(
                OAUTH_TOKEN, accessToken.getToken(),
                OAUTH_TOKEN_SECRET, accessToken.getTokenSecret(),
                OAUTH_EXPIRES_IN, Long.toString(accessToken.getTimeToLive() / 1000),
                OAUTH_SESSION_HANDLE, accessToken.getSession().getHandle(),
                OAUTH_AUTHORIZATION_EXPIRES_IN, Long.toString(accessToken.getSession().getTimeToLive() / 1000)
        ), out);
    }

    private void checkRequestToken(OAuthMessage requestMessage, ServiceProviderToken token) throws Exception {
        if (token.hasExpired(clock)) {
            throw new OAuthProblemException(TOKEN_EXPIRED);
        }
        if (token.getAuthorization() == ServiceProviderToken.Authorization.NONE) {
            throw new OAuthProblemException(PERMISSION_UNKNOWN);
        }
        if (token.getAuthorization() == ServiceProviderToken.Authorization.DENIED) {
            throw new OAuthProblemException(PERMISSION_DENIED);
        }
        if (!token.getConsumer().getKey().equals(requestMessage.getConsumerKey())) {
            throw new OAuthProblemException(TOKEN_REJECTED);
        }

        requestMessage.requireParameters(OAUTH_VERIFIER);
        if (!token.getVerifier().equals(requestMessage.getParameter(OAUTH_VERIFIER))) {
            throw new OAuthProblemException(TOKEN_REJECTED);
        }
    }

    private void checkAccessToken(OAuthMessage requestMessage, ServiceProviderToken token) throws Exception {
        if (token.getSession() == null) {
            throw new OAuthProblemException(TOKEN_REJECTED);
        }
        requestMessage.requireParameters(OAUTH_SESSION_HANDLE);
        if (!token.getSession().getHandle().equals(requestMessage.getParameter(OAUTH_SESSION_HANDLE))) {
            throw new OAuthProblemException(TOKEN_REJECTED);
        }
        if (token.getSession().hasExpired(clock)) {
            throw new OAuthProblemException(PERMISSION_DENIED);
        }
    }
}
