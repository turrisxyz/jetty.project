//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.ee10.servlet.security.authentication;

import java.io.IOException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Locale;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import jakarta.servlet.http.HttpSession;
import org.eclipse.jetty.ee10.servlet.ServletContextRequest;
import org.eclipse.jetty.ee10.servlet.ServletContextResponse;
import org.eclipse.jetty.ee10.servlet.security.Authentication;
import org.eclipse.jetty.ee10.servlet.security.Authentication.User;
import org.eclipse.jetty.ee10.servlet.security.ServerAuthException;
import org.eclipse.jetty.ee10.servlet.security.UserAuthentication;
import org.eclipse.jetty.ee10.servlet.security.UserIdentity;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpHeaderValue;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.MimeTypes;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Fields;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.URIUtil;
import org.eclipse.jetty.util.security.Constraint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * FORM Authenticator.
 *
 * <p>This authenticator implements form authentication will use dispatchers to
 * the login page if the {@link #__FORM_DISPATCH} init parameter is set to true.
 * Otherwise it will redirect.</p>
 *
 * <p>The form authenticator redirects unauthenticated requests to a log page
 * which should use a form to gather username/password from the user and send them
 * to the /j_security_check URI within the context.  FormAuthentication uses
 * {@link SessionAuthentication} to wrap Authentication results so that they
 * are  associated with the session.</p>
 */
public class FormAuthenticator extends LoginAuthenticator
{
    private static final Logger LOG = LoggerFactory.getLogger(FormAuthenticator.class);

    public static final String __FORM_LOGIN_PAGE = "org.eclipse.jetty.security.form_login_page";
    public static final String __FORM_ERROR_PAGE = "org.eclipse.jetty.security.form_error_page";
    public static final String __FORM_DISPATCH = "org.eclipse.jetty.security.dispatch";
    public static final String __J_URI = "org.eclipse.jetty.security.form_URI";
    public static final String __J_POST = "org.eclipse.jetty.security.form_POST";
    public static final String __J_METHOD = "org.eclipse.jetty.security.form_METHOD";
    public static final String __J_SECURITY_CHECK = "/j_security_check";
    public static final String __J_USERNAME = "j_username";
    public static final String __J_PASSWORD = "j_password";

    private String _formErrorPage;
    private String _formErrorPath;
    private String _formLoginPage;
    private String _formLoginPath;
    private boolean _dispatch;
    private boolean _alwaysSaveUri;

    public FormAuthenticator()
    {
    }

    public FormAuthenticator(String login, String error, boolean dispatch)
    {
        this();
        if (login != null)
            setLoginPage(login);
        if (error != null)
            setErrorPage(error);
        _dispatch = dispatch;
    }

    /**
     * If true, uris that cause a redirect to a login page will always
     * be remembered. If false, only the first uri that leads to a login
     * page redirect is remembered.
     * See https://bugs.eclipse.org/bugs/show_bug.cgi?id=379909
     *
     * @param alwaysSave true to always save the uri
     */
    public void setAlwaysSaveUri(boolean alwaysSave)
    {
        _alwaysSaveUri = alwaysSave;
    }

    public boolean getAlwaysSaveUri()
    {
        return _alwaysSaveUri;
    }

    @Override
    public void setConfiguration(AuthConfiguration configuration)
    {
        super.setConfiguration(configuration);
        String login = configuration.getInitParameter(FormAuthenticator.__FORM_LOGIN_PAGE);
        if (login != null)
            setLoginPage(login);
        String error = configuration.getInitParameter(FormAuthenticator.__FORM_ERROR_PAGE);
        if (error != null)
            setErrorPage(error);
        String dispatch = configuration.getInitParameter(FormAuthenticator.__FORM_DISPATCH);
        _dispatch = dispatch == null ? _dispatch : Boolean.parseBoolean(dispatch);
    }

    @Override
    public String getAuthMethod()
    {
        return Constraint.__FORM_AUTH;
    }

    private void setLoginPage(String path)
    {
        if (!path.startsWith("/"))
        {
            LOG.warn("form-login-page must start with /");
            path = "/" + path;
        }
        _formLoginPage = path;
        _formLoginPath = path;
        if (_formLoginPath.indexOf('?') > 0)
            _formLoginPath = _formLoginPath.substring(0, _formLoginPath.indexOf('?'));
    }

    private void setErrorPage(String path)
    {
        if (path == null || path.trim().length() == 0)
        {
            _formErrorPath = null;
            _formErrorPage = null;
        }
        else
        {
            if (!path.startsWith("/"))
            {
                LOG.warn("form-error-page must start with /");
                path = "/" + path;
            }
            _formErrorPage = path;
            _formErrorPath = path;

            if (_formErrorPath.indexOf('?') > 0)
                _formErrorPath = _formErrorPath.substring(0, _formErrorPath.indexOf('?'));
        }
    }

    @Override
    public UserIdentity login(String username, Object password, ServletRequest request)
    {

        UserIdentity user = super.login(username, password, request);
        if (user != null)
        {

            HttpSession session = ((HttpServletRequest)request).getSession(true);
            Authentication cached = new SessionAuthentication(getAuthMethod(), user, password);
            session.setAttribute(SessionAuthentication.__J_AUTHENTICATED, cached);
        }
        return user;
    }

    @Override
    public void logout(ServletRequest request)
    {
        super.logout(request);
        HttpServletRequest httpRequest = (HttpServletRequest)request;
        HttpSession session = httpRequest.getSession(false);

        if (session == null)
            return;

        //clean up session
        session.removeAttribute(SessionAuthentication.__J_AUTHENTICATED);
    }

    @Override
    public void prepareRequest(ServletRequest request)
    {
        //if this is a request resulting from a redirect after auth is complete
        //(ie its from a redirect to the original request uri) then due to 
        //browser handling of 302 redirects, the method may not be the same as
        //that of the original request. Replace the method and original post
        //params (if it was a post).
        //
        //See Servlet Spec 3.1 sec 13.6.3
        HttpServletRequest httpRequest = (HttpServletRequest)request;
        HttpSession session = httpRequest.getSession(false);
        if (session == null || session.getAttribute(SessionAuthentication.__J_AUTHENTICATED) == null)
            return; //not authenticated yet

        String juri = (String)session.getAttribute(__J_URI);
        if (juri == null || juri.length() == 0)
            return; //no original uri saved

        String method = (String)session.getAttribute(__J_METHOD);
        if (method == null || method.length() == 0)
            return; //didn't save original request method

        StringBuffer buf = httpRequest.getRequestURL();
        if (httpRequest.getQueryString() != null)
            buf.append("?").append(httpRequest.getQueryString());

        if (!juri.equals(buf.toString()))
            return; //this request is not for the same url as the original

        //restore the original request's method on this request
        if (LOG.isDebugEnabled())
            LOG.debug("Restoring original method {} for {} with method {}", method, juri, httpRequest.getMethod());
        ServletContextRequest baseRequest = ServletContextRequest.getBaseRequest(request);
        baseRequest.getServletApiRequest().setMethod(method);
    }

    @Override
    public Authentication validateRequest(ServletRequest req, ServletResponse res, boolean mandatory) throws ServerAuthException
    {
        final HttpServletRequest httpRequest = (HttpServletRequest)req;
        final HttpServletResponse httpResponse = (HttpServletResponse)res;
        final ServletContextRequest baseRequest = ServletContextRequest.getBaseRequest(req); //unwrapped original request
        final ServletContextResponse baseResponse = baseRequest.getResponse(); //unwrapped original response

        String uri = httpRequest.getRequestURI();
        if (uri == null)
            uri = URIUtil.SLASH;

        mandatory |= isJSecurityCheck(uri);
        if (!mandatory)
            return new DeferredAuthentication(this);

        if (isLoginOrErrorPage(baseRequest.getPathInContext()) && !DeferredAuthentication.isDeferred(httpResponse))
            return new DeferredAuthentication(this);

        try
        {
            // Handle a request for authentication.
            if (isJSecurityCheck(uri))
            {
                final String username = httpRequest.getParameter(__J_USERNAME);
                final String password = httpRequest.getParameter(__J_PASSWORD);

                UserIdentity user = login(username, password, httpRequest);
                LOG.debug("jsecuritycheck {} {}", username, user);
                HttpSession session = httpRequest.getSession(false);
                if (user != null)
                {
                    // Redirect to original request
                    String nuri;
                    FormAuthentication formAuth;
                    synchronized (session)
                    {
                        nuri = (String)session.getAttribute(__J_URI);

                        if (nuri == null || nuri.length() == 0)
                        {
                            nuri = httpRequest.getContextPath();
                            if (nuri.length() == 0)
                                nuri = URIUtil.SLASH;
                        }
                        formAuth = new FormAuthentication(getAuthMethod(), user);
                    }
                    LOG.debug("authenticated {}->{}", formAuth, nuri);

                    baseResponse.setContentLength(0);
                    Response.sendRedirect(baseRequest.getWrapped(), baseResponse.getWrapped(), null, httpResponse.encodeRedirectURL(nuri));
                    return formAuth;
                }

                // not authenticated
                if (LOG.isDebugEnabled())
                    LOG.debug("Form authentication FAILED for {}", StringUtil.printable(username));
                if (_formErrorPage == null)
                {
                    LOG.debug("auth failed {}->403", username);
                    if (httpResponse != null)
                        httpResponse.sendError(HttpServletResponse.SC_FORBIDDEN);
                }
                else if (_dispatch)
                {
                    LOG.debug("auth failed {}=={}", username, _formErrorPage);
                    RequestDispatcher dispatcher = httpRequest.getRequestDispatcher(_formErrorPage);
                    httpResponse.setHeader(HttpHeader.CACHE_CONTROL.asString(), HttpHeaderValue.NO_CACHE.asString());
                    httpResponse.setDateHeader(HttpHeader.EXPIRES.asString(), 1);
                    dispatcher.forward(new FormRequest(httpRequest), new FormResponse(httpResponse));
                }
                else
                {
                    LOG.debug("auth failed {}->{}", username, _formErrorPage);
                    Response.sendRedirect(baseRequest.getWrapped(), baseResponse.getWrapped(), null, httpResponse.encodeRedirectURL(URIUtil.addPaths(httpRequest.getContextPath(), _formErrorPage)));
                }

                return Authentication.SEND_FAILURE;
            }

            // Look for cached authentication
            HttpSession session = httpRequest.getSession(false);
            Authentication authentication = session == null ? null : (Authentication)session.getAttribute(SessionAuthentication.__J_AUTHENTICATED);
            if (authentication != null)
            {
                // Has authentication been revoked?
                if (authentication instanceof Authentication.User &&
                    _loginService != null &&
                    !_loginService.validate(((Authentication.User)authentication).getUserIdentity()))
                {
                    LOG.debug("auth revoked {}", authentication);
                    session.removeAttribute(SessionAuthentication.__J_AUTHENTICATED);
                }
                else
                {
                    synchronized (session)
                    {
                        String jUri = (String)session.getAttribute(__J_URI);
                        if (jUri != null)
                        {
                            //check if the request is for the same url as the original and restore
                            //params if it was a post
                            LOG.debug("auth retry {}->{}", authentication, jUri);
                            StringBuffer buf = httpRequest.getRequestURL();
                            if (httpRequest.getQueryString() != null)
                                buf.append("?").append(httpRequest.getQueryString());

                            if (jUri.equals(buf.toString()))
                            {
                                Fields jPost = (Fields)session.getAttribute(__J_POST);
                                if (jPost != null)
                                {
                                    LOG.debug("auth rePOST {}->{}", authentication, jUri);
                                    baseRequest.getServletApiRequest().setContentParameters(jPost);
                                }
                                session.removeAttribute(__J_URI);
                                session.removeAttribute(__J_METHOD);
                                session.removeAttribute(__J_POST);
                            }
                        }
                    }
                    LOG.debug("auth {}", authentication);
                    return authentication;
                }
            }

            // if we can't send challenge
            if (DeferredAuthentication.isDeferred(httpResponse))
            {
                LOG.debug("auth deferred {}", session == null ? null : session.getId());
                return Authentication.UNAUTHENTICATED;
            }

            // remember the current URI
            session = (session != null ? session : httpRequest.getSession(true));
            synchronized (session)
            {
                // But only if it is not set already, or we save every uri that leads to a login form redirect
                if (session.getAttribute(__J_URI) == null || _alwaysSaveUri)
                {
                    StringBuffer buf = httpRequest.getRequestURL();
                    if (httpRequest.getQueryString() != null)
                        buf.append("?").append(httpRequest.getQueryString());
                    session.setAttribute(__J_URI, buf.toString());
                    session.setAttribute(__J_METHOD, httpRequest.getMethod());

                    if (MimeTypes.Type.FORM_ENCODED.is(req.getContentType()) && HttpMethod.POST.is(httpRequest.getMethod()))
                    {
                        session.setAttribute(__J_POST, baseRequest.getServletApiRequest().getContentParameters());
                    }
                }
            }

            // send the the challenge
            if (_dispatch)
            {
                LOG.debug("challenge {}=={}", session.getId(), _formLoginPage);
                RequestDispatcher dispatcher = httpRequest.getRequestDispatcher(_formLoginPage);
                httpResponse.setHeader(HttpHeader.CACHE_CONTROL.asString(), HttpHeaderValue.NO_CACHE.asString());
                httpResponse.setDateHeader(HttpHeader.EXPIRES.asString(), 1);
                dispatcher.forward(new FormRequest(httpRequest), new FormResponse(httpResponse));
            }
            else
            {
                LOG.debug("challenge {}->{}", session.getId(), _formLoginPage);
                Response.sendRedirect(baseRequest, baseResponse, null, httpResponse.encodeRedirectURL(URIUtil.addPaths(httpRequest.getContextPath(), _formLoginPage)));
            }
            return Authentication.SEND_CONTINUE;
        }
        catch (IOException | ServletException e)
        {
            throw new ServerAuthException(e);
        }
    }

    public boolean isJSecurityCheck(String uri)
    {
        int jsc = uri.indexOf(__J_SECURITY_CHECK);

        if (jsc < 0)
            return false;
        int e = jsc + __J_SECURITY_CHECK.length();
        if (e == uri.length())
            return true;
        char c = uri.charAt(e);
        return c == ';' || c == '#' || c == '/' || c == '?';
    }

    public boolean isLoginOrErrorPage(String pathInContext)
    {
        return pathInContext != null && (pathInContext.equals(_formErrorPath) || pathInContext.equals(_formLoginPath));
    }

    @Override
    public boolean secureResponse(ServletRequest req, ServletResponse res, boolean mandatory, User validatedUser) throws ServerAuthException
    {
        return true;
    }

    protected static class FormRequest extends HttpServletRequestWrapper
    {
        public FormRequest(HttpServletRequest request)
        {
            super(request);
        }

        @Override
        public long getDateHeader(String name)
        {
            if (name.toLowerCase(Locale.ENGLISH).startsWith("if-"))
                return -1;
            return super.getDateHeader(name);
        }

        @Override
        public String getHeader(String name)
        {
            if (name.toLowerCase(Locale.ENGLISH).startsWith("if-"))
                return null;
            return super.getHeader(name);
        }

        @Override
        public Enumeration<String> getHeaderNames()
        {
            return Collections.enumeration(Collections.list(super.getHeaderNames()));
        }

        @Override
        public Enumeration<String> getHeaders(String name)
        {
            if (name.toLowerCase(Locale.ENGLISH).startsWith("if-"))
                return Collections.<String>enumeration(Collections.<String>emptyList());
            return super.getHeaders(name);
        }
    }

    protected static class FormResponse extends HttpServletResponseWrapper
    {
        public FormResponse(HttpServletResponse response)
        {
            super(response);
        }

        @Override
        public void addDateHeader(String name, long date)
        {
            if (notIgnored(name))
                super.addDateHeader(name, date);
        }

        @Override
        public void addHeader(String name, String value)
        {
            if (notIgnored(name))
                super.addHeader(name, value);
        }

        @Override
        public void setDateHeader(String name, long date)
        {
            if (notIgnored(name))
                super.setDateHeader(name, date);
        }

        @Override
        public void setHeader(String name, String value)
        {
            if (notIgnored(name))
                super.setHeader(name, value);
        }

        private boolean notIgnored(String name)
        {
            if (HttpHeader.CACHE_CONTROL.is(name) ||
                HttpHeader.PRAGMA.is(name) ||
                HttpHeader.ETAG.is(name) ||
                HttpHeader.EXPIRES.is(name) ||
                HttpHeader.LAST_MODIFIED.is(name) ||
                HttpHeader.AGE.is(name))
                return false;
            return true;
        }
    }

    /**
     * This Authentication represents a just completed Form authentication.
     * Subsequent requests from the same user are authenticated by the presents
     * of a {@link SessionAuthentication} instance in their session.
     */
    public static class FormAuthentication extends UserAuthentication implements Authentication.ResponseSent
    {
        public FormAuthentication(String method, UserIdentity userIdentity)
        {
            super(method, userIdentity);
        }

        @Override
        public String toString()
        {
            return "Form" + super.toString();
        }
    }
}