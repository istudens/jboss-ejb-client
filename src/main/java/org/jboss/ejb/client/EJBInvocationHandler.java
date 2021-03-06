/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2017 Red Hat, Inc., and individual contributors
 * as indicated by the @author tags.
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

package org.jboss.ejb.client;

import static java.security.AccessController.doPrivileged;

import java.io.Serializable;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.rmi.RemoteException;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;

import javax.ejb.EJBException;
import javax.ejb.EJBHome;
import javax.ejb.EJBObject;
import javax.net.ssl.SSLContext;

import org.jboss.ejb._private.Logs;
import org.wildfly.common.Assert;
import org.wildfly.security.auth.client.AuthenticationConfiguration;
import org.wildfly.security.auth.client.AuthenticationContext;
import org.wildfly.security.auth.client.AuthenticationContextConfigurationClient;

/**
 * @param <T> the proxy view type
 * @author <a href="mailto:david.lloyd@redhat.com">David M. Lloyd</a>
 */
final class EJBInvocationHandler<T> extends Attachable implements InvocationHandler, Serializable {

    private static final long serialVersionUID = 946555285095057230L;
    private static final AuthenticationContextConfigurationClient CLIENT = doPrivileged(AuthenticationContextConfigurationClient.ACTION);

    private final transient boolean async;

    private transient String toString;
    private transient String toStringProxy;

    private final AtomicReference<EJBLocator<T>> locatorRef;

    private volatile Affinity weakAffinity = Affinity.NONE;

    // -1 = use global value
    private volatile long invocationTimeout = -1L;

    /**
     * The sticky authentication configuration for this proxy.
     */
    private volatile AuthenticationConfiguration authenticationConfiguration;
    /**
     * The sticky SSL context for this proxy.
     */
    private volatile SSLContext sslContext;

    /**
     * Construct a new instance.
     *
     * @param locator the initial EJB locator (not {@code null})
     * @param authenticationConfiguration the sticky authentication configuration, or {@code null} to use a dynamic identity
     * @param sslContext the sticky SSL context, or {@code null} to use a dynamic SSL context
     */
    EJBInvocationHandler(final EJBLocator<T> locator, final AuthenticationConfiguration authenticationConfiguration, final SSLContext sslContext) {
        this.authenticationConfiguration = authenticationConfiguration;
        this.sslContext = sslContext;
        Assert.checkNotNullParam("locator", locator);
        this.locatorRef = new AtomicReference<>(locator);
        async = false;
        if (locator instanceof StatefulEJBLocator) {
            // set the weak affinity to the node on which the session was created
            setWeakAffinity(locator.getAffinity());
        }
    }

    /**
     * Construct a new asynchronous instance.
     *
     * @param other the synchronous invocation handler
     */
    EJBInvocationHandler(final EJBInvocationHandler<T> other) {
        super(other);
        final EJBLocator<T> locator = other.locatorRef.get();
        locatorRef = new AtomicReference<>(locator);
        authenticationConfiguration = other.authenticationConfiguration;
        sslContext = other.sslContext;
        async = true;
        if (locator instanceof StatefulEJBLocator) {
            // set the weak affinity to the node on which the session was created
            setWeakAffinity(locator.getAffinity());
        }
    }

    public Object invoke(final Object rawProxy, final Method method, final Object... args) throws Exception {
        final T proxy = locatorRef.get().getViewType().cast(rawProxy);
        final EJBProxyInformation.ProxyMethodInfo methodInfo = locatorRef.get().getProxyInformation().getProxyMethodInfo(method);
        return invoke(proxy, methodInfo, args);
    }

    EJBProxyInformation.ProxyMethodInfo getProxyMethodInfo(EJBMethodLocator methodLocator) {
        return locatorRef.get().getProxyInformation().getProxyMethodInfo(methodLocator);
    }

    Object invoke(final Object proxy, final EJBProxyInformation.ProxyMethodInfo methodInfo, final Object... args) throws Exception {
        final Method method = methodInfo.getMethod();
        switch (methodInfo.getMethodType()) {
            case EJBProxyInformation.MT_EQUALS:
            case EJBProxyInformation.MT_IS_IDENTICAL: {
                assert args.length == 1; // checked by EJBProxyInformation
                if (args[0] instanceof Proxy) {
                    final InvocationHandler handler = Proxy.getInvocationHandler(args[0]);
                    if (handler instanceof EJBInvocationHandler) {
                        return Boolean.valueOf(equals(handler));
                    }
                }
                return Boolean.FALSE;
            }
            case EJBProxyInformation.MT_HASH_CODE: {
                // TODO: cache instance?
                return Integer.valueOf(locatorRef.get().hashCode());
            }
            case EJBProxyInformation.MT_TO_STRING: {
                final String s = toStringProxy;
                return s != null ? s : (toStringProxy = String.format("Proxy for remote EJB %s", locatorRef.get()));
            }
            case EJBProxyInformation.MT_GET_PRIMARY_KEY: {
                if (locatorRef.get().isEntity()) {
                    return locatorRef.get().narrowAsEntity(EJBObject.class).getPrimaryKey();
                }
                throw new RemoteException("Cannot invoke getPrimaryKey() on " + proxy);
            }
            case EJBProxyInformation.MT_GET_HANDLE: {
                // TODO: cache instance
                return EJBHandle.create(locatorRef.get().narrowTo(EJBObject.class));
            }
            case EJBProxyInformation.MT_GET_HOME_HANDLE: {
                if (locatorRef.get() instanceof EJBHomeLocator) {
                    // TODO: cache instance
                    return EJBHomeHandle.create(locatorRef.get().narrowAsHome(EJBHome.class));
                }
                throw new RemoteException("Cannot invoke getHomeHandle() on " + proxy);
            }
        }
        // otherwise it's a business method
        assert methodInfo.getMethodType() == EJBProxyInformation.MT_BUSINESS;
        final EJBClientContext clientContext = EJBClientContext.getCurrent();
        return clientContext.performLocatedAction(locatorRef.get(), (receiver, originalLocator, newAffinity, authenticationConfiguration, sslContext) -> {
            final EJBClientInvocationContext invocationContext = new EJBClientInvocationContext(this, clientContext, proxy, args, methodInfo);
            invocationContext.setReceiver(receiver);
            invocationContext.setLocator(locatorRef.get().withNewAffinity(newAffinity));
            invocationContext.setBlockingCaller(true);
            final AuthenticationContext context = AuthenticationContext.captureCurrent();
            invocationContext.setAuthenticationConfiguration(authenticationConfiguration == null ? CLIENT.getAuthenticationConfiguration(newAffinity.getUri(), context, -1, "ejb", "jboss") : authenticationConfiguration);
            invocationContext.setSSLContext(sslContext == null ? CLIENT.getSSLContext(newAffinity.getUri(), context, "ejb", "jboss") : sslContext);
            invocationContext.setWeakAffinity(getWeakAffinity());

            try {
                // send the request
                invocationContext.sendRequest();

                if (! async && ! methodInfo.isClientAsync()) {
                    // wait for invocation to complete
                    final Object value = invocationContext.awaitResponse(this);
                    if (value != EJBClientInvocationContext.PROCEED_ASYNC) {
                        return value;
                    }
                    // proceed asynchronously
                }
                invocationContext.setBlockingCaller(false);
                // force async...
                if (method.getReturnType() == Future.class) {
                    return invocationContext.getFutureResponse();
                } else if (method.getReturnType() == void.class) {
                    invocationContext.setDiscardResult();
                    // Void return
                    return null;
                } else {
                    // wrap return always
                    EJBClient.setFutureResult(invocationContext.getFutureResponse());
                    return null;
                }
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                boolean remoteException = false;
                for (Class<?> exception : method.getExceptionTypes()) {
                    if (exception.isAssignableFrom(e.getClass())) {
                        throw e;
                    } else if (RemoteException.class.equals(exception)) {
                        remoteException = true;
                    }
                }
                if (remoteException) {
                    throw new RemoteException("Error", e);
                }
                throw new EJBException(e);
            }
        }, weakAffinity, authenticationConfiguration, sslContext);
    }

    void setWeakAffinity(Affinity newWeakAffinity) {
        weakAffinity = newWeakAffinity;
    }

    Affinity getWeakAffinity() {
        return weakAffinity;
    }

    @SuppressWarnings("unchecked")
    static <T> EJBInvocationHandler<? extends T> forProxy(T proxy) {
        InvocationHandler handler = Proxy.getInvocationHandler(proxy);
        if (handler instanceof EJBInvocationHandler) {
            return (EJBInvocationHandler<? extends T>) handler;
        }
        throw Logs.MAIN.proxyNotOurs(proxy, EJBClient.class.getName());
    }

    @SuppressWarnings("unused")
    protected Object writeReplace() {
        return new SerializedEJBInvocationHandler(locatorRef.get(), async);
    }

    EJBInvocationHandler<T> getAsyncHandler() {
        return async ? this : new EJBInvocationHandler<T>(this);
    }

    boolean isAsyncHandler() {
        return this.async;
    }

    EJBLocator<T> getLocator() {
        return locatorRef.get();
    }

    /**
     * Determine whether this object is equal to another.
     *
     * @param other the other object
     * @return {@code true} if they are equal, {@code false} otherwise
     */
    public boolean equals(Object other) {
        return other instanceof EJBInvocationHandler && equals((EJBInvocationHandler<?>)other);
    }

    /**
     * Determine whether this object is equal to another.
     *
     * @param other the other object
     * @return {@code true} if they are equal, {@code false} otherwise
     */
    public boolean equals(InvocationHandler other) {
        return other instanceof EJBInvocationHandler && equals((EJBInvocationHandler<?>)other);
    }

    /**
     * Determine whether this object is equal to another.
     *
     * @param other the other object
     * @return {@code true} if they are equal, {@code false} otherwise
     */
    public boolean equals(EJBInvocationHandler<?> other) {
        return this == other || other != null && locatorRef.get().equals(other.locatorRef.get()) && async == other.async;
    }

    /**
     * Get the hash code of this handler.
     *
     * @return the hash code of this handler
     */
    public int hashCode() {
        int hc = locatorRef.get().hashCode();
        if (async) hc ++;
        return hc;
    }

    public String toString() {
        final String s = toString;
        return s != null ? s : (toString = String.format("Proxy invocation handler for %s", locatorRef.get()));
    }

    void setStrongAffinity(final Affinity newAffinity) {
        final AtomicReference<EJBLocator<T>> locatorRef = this.locatorRef;
        EJBLocator<T> oldVal, newVal;
        do {
            oldVal = locatorRef.get();
            if (oldVal.getAffinity().equals(newAffinity)) {
                return;
            }
            newVal = oldVal.withNewAffinity(newAffinity);
        } while (! locatorRef.compareAndSet(oldVal, newVal));
    }

    void setSessionID(final SessionID sessionID) {
        final AtomicReference<EJBLocator<T>> locatorRef = this.locatorRef;
        EJBLocator<T> oldVal, newVal;
        do {
            oldVal = locatorRef.get();
            if (oldVal.isStateful()) {
                if (oldVal.asStateful().getSessionId().equals(sessionID)) {
                    // harmless/idempotent
                    return;
                }
                throw Logs.MAIN.ejbIsAlreadyStateful();
            }
            newVal = oldVal.withSession(sessionID);
        } while (! locatorRef.compareAndSet(oldVal, newVal));
    }

    long getInvocationTimeout() {
        return invocationTimeout;
    }

    void setInvocationTimeout(final long invocationTimeout) {
        this.invocationTimeout = invocationTimeout;
    }

    boolean compareAndSetStrongAffinity(final Affinity expectedAffinity, final Affinity newAffinity) {
        Assert.checkNotNullParam("expectedAffinity", expectedAffinity);
        Assert.checkNotNullParam("newAffinity", newAffinity);
        final AtomicReference<EJBLocator<T>> locatorRef = this.locatorRef;
        EJBLocator<T> oldVal = locatorRef.get();
        if (! oldVal.getAffinity().equals(expectedAffinity)) {
            return false;
        }
        EJBLocator<T> newVal = oldVal.withNewAffinity(newAffinity);
        return locatorRef.compareAndSet(oldVal, newVal);
    }
}
