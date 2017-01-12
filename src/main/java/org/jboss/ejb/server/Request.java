/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2016, Red Hat, Inc., and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */

package org.jboss.ejb.server;

import java.net.SocketAddress;
import java.util.concurrent.Executor;

import javax.transaction.SystemException;
import javax.transaction.Transaction;

import org.jboss.ejb.client.Affinity;
import org.jboss.ejb.client.EJBIdentifier;
import org.jboss.ejb.client.SessionID;
import org.wildfly.common.annotation.NotNull;
import org.wildfly.common.function.ExceptionSupplier;
import org.wildfly.security.auth.server.SecurityIdentity;

/**
 * The base type of any EJB server request.  This type is implemented by protocol implementations and consumed by
 * EJB invocation servers.
 *
 * @author <a href="mailto:david.lloyd@redhat.com">David M. Lloyd</a>
 */
public interface Request {
    /**
     * Get the request executor.  This is an executor which is associated with the transport provider which may be
     * used to execute requests.
     *
     * @return the request executor
     */
    Executor getRequestExecutor();

    /**
     * Get the peer address that the request was received from, if known.
     *
     * @return the peer address, or {@code null} if it is not known
     */
    default SocketAddress getPeerAddress() {
        return null;
    }

    /**
     * Get the local address that the request was received to, if known.
     *
     * @return the local address, or {@code null} if it is not known
     */
    default SocketAddress getLocalAddress() {
        return null;
    }

    /**
     * Get the protocol of this request.
     *
     * @return the protocol of this request (must not be {@code null})
     */
    String getProtocol();

    /**
     * Get the weak affinity of the request.
     *
     * @return the weak affinity of the request (must not be {@code null})
     */
    @NotNull
    default Affinity getWeakAffinity() {
        return Affinity.NONE;
    }

    /**
     * Determine if the request has a transaction.
     *
     * @return {@code true} if there is a transaction context with this request
     */
    boolean hasTransaction();

    /**
     * Get the inflowed transaction of the request.  This should not be called unless it is desired to actually inflow
     * the transaction; doing so without using the transaction will cause needless work for the transaction coordinator.
     * To perform transaction checks, use {@link #hasTransaction()} first.  This method should only be called one time
     * as it will inflow the transaction when called.
     * <p>
     * If a transaction is present but transaction inflow has failed, a {@link SystemException} is thrown.  In this case,
     * the invocation should fail.
     * <p>
     * It is the caller's responsibility to check the status of the returned transaction to ensure that it is in an
     * active state; failure to do so can result in undesirable behavior.
     *
     * @return the transaction, or {@code null} if there is none for the request
     * @throws SystemException if inflowing the transaction failed
     * @throws IllegalStateException if this method is called more than one time
     */
    Transaction getTransaction() throws SystemException, IllegalStateException;

    /**
     * Determine if this request is blocking a local thread.
     *
     * @return {@code true} if the request is blocking the caller thread, {@code false} otherwise
     */
    boolean isBlockingCaller();

    /**
     * Get the identifier of the target EJB.
     *
     * @return the identifier of the target EJB (must not be {@code null})
     */
    @NotNull
    EJBIdentifier getEJBIdentifier();

    /**
     * Write a message indicating that an exception was thrown by the operation.
     *
     * @param exception the exception that was thrown (must not be {@code null})
     */
    void writeException(@NotNull Exception exception);

    /**
     * Write a message indicating that the EJB is not found on this server.  The request should be abandoned after
     * invoking this method.
     */
    void writeNoSuchEJB();

    /**
     * Write a response indicating that the request was successfully cancelled.
     */
    void writeCancelResponse();

    /**
     * Write a message indicating that given EJB is not actually stateful.  The request should be abandoned after
     * invoking this method.
     */
    void writeNotStateful();

    /**
     * Attempt to convert the current invocation into a stateful invocation.  For session creation requests, this method
     * <em>must</em> be called.  For regular method invocations, this method <em>may</em> be called if the invoked EJB
     * is stateful but the locator is stateless, in order to auto-create the session.
     *
     * @param sessionId the new session ID (must not be {@code null})
     * @throws IllegalArgumentException if the current invocation cannot be converted to a stateful invocation because
     *  it is already stateful or the target EJB is not a stateful EJB
     * @throws IllegalStateException if the invocation was already converted to be stateful with a different session ID
     */
    void convertToStateful(@NotNull SessionID sessionId) throws IllegalArgumentException, IllegalStateException;
}