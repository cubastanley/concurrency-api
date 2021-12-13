/*
 * Copyright (c) 2021 Contributors to the Eclipse Foundation
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v. 2.0, which is available at
 * http://www.eclipse.org/legal/epl-2.0.
 *
 * This Source Code may also be made available under the following Secondary
 * Licenses when the conditions for such availability set forth in the
 * Eclipse Public License v. 2.0 are satisfied: GNU General Public License,
 * version 2 with the GNU Classpath Exception, which is available at
 * https://www.gnu.org/software/classpath/license.html.
 *
 * SPDX-License-Identifier: EPL-2.0 OR GPL-2.0 WITH Classpath-exception-2.0
 */
package jakarta.enterprise.concurrent.spec.ManagedThreadFactory.resourcedef;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertTrue;

import java.io.IOException;
import java.util.Arrays;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinTask;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import jakarta.annotation.Resource;
import jakarta.enterprise.concurrent.ContextService;
import jakarta.enterprise.concurrent.ManagedThreadFactory;
import jakarta.enterprise.concurrent.ManagedThreadFactoryDefinition;
import jakarta.enterprise.concurrent.spec.context.IntContext;
import jakarta.enterprise.concurrent.spec.context.StringContext;
import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.transaction.Status;
import jakarta.transaction.UserTransaction;

import javax.naming.InitialContext;
import javax.naming.NamingException;

@ManagedThreadFactoryDefinition(name = "java:app/concurrent/ThreadFactoryA",
                                context = "java:app/concurrent/ContextA",
                                priority = 4)
@ManagedThreadFactoryDefinition(name = "java:comp/concurrent/ThreadFactoryB")
@WebServlet("/ManagedThreadFactoryDefinitionServlet")
public class ManagedThreadFactoryDefinitionServlet extends HttpServlet {
    private static final long serialVersionUID = 1L;
    private static final long MAX_WAIT_SECONDS = TimeUnit.MINUTES.toSeconds(2);
    private static final String SUCCESS = "success";

    @Resource
    UserTransaction tx;

    @Override
    protected void service(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        String action = req.getParameter("action");
        System.out.println("STARTING " + getClass().getName() + "." + action);
        try {
            String result;

            if ("testManagedThreadFactoryDefinitionAllAttributes".equals(action))
                result = testManagedThreadFactoryDefinitionAllAttributes();
            else if ("testManagedThreadFactoryDefinitionDefaults".equals(action))
                result = testManagedThreadFactoryDefinitionDefaults();
            else if ("testParallelStreamBackedByManagedThreadFactory".equals(action))
                result = testParallelStreamBackedByManagedThreadFactory();
            else
                result = "unknown or missing action for " + getClass().getName() + ": " + action;

            System.out.println((SUCCESS.equals(result) ? "PASSED" : "FAILED") +
                               getClass().getName() + "." + action + ": " + result);
            resp.getWriter().println(result);
        } catch (Throwable x) {
            System.out.print("FAILED " + getClass().getName() + "." + action + ": ");
            x.printStackTrace(System.out);
            x.printStackTrace(resp.getWriter());
        }
    }

    /**
     * A ManagedThreadFactoryDefinition with all attributes configured enforces priority and propagates context.
     */
    private String testManagedThreadFactoryDefinitionAllAttributes() throws Throwable {
        try {
            IntContext.set(161);
            StringContext.set("testManagedThreadFactoryDefinitionAllAttributes-1");

            ManagedThreadFactory threadFactory = InitialContext.doLookup("java:app/concurrent/ThreadFactoryA");

            IntContext.set(162);
            StringContext.set("testManagedThreadFactoryDefinitionAllAttributes-2");

            Thread thread1 = threadFactory.newThread(() -> {});
            assertEquals(4, thread1.getPriority(),
                         "New threads must be created with the priority that is specified on " +
                         "ManagedThreadFactoryDefinition");

            BlockingQueue<Object> results = new LinkedBlockingQueue<Object>();

            threadFactory.newThread(() -> {
                results.add(Thread.currentThread().getPriority());
                results.add(StringContext.get());
                results.add(IntContext.get());
                try {
                    results.add(InitialContext.doLookup("java:app/concurrent/ContextA"));
                } catch (Throwable x) {
                    results.add(x);
                }
            }).start();

            assertEquals(Integer.valueOf(4), results.poll(MAX_WAIT_SECONDS, TimeUnit.SECONDS),
                         "ManagedThreadFactory must start threads with the configured priority.");

            assertEquals("", results.poll(MAX_WAIT_SECONDS, TimeUnit.SECONDS),
                         "Third-party context type StringContext must be cleared from thread " +
                         "per ManagedThreadFactoryDefinition and ContextServiceDefinition configuration.");

            assertEquals(Integer.valueOf(161), results.poll(MAX_WAIT_SECONDS, TimeUnit.SECONDS),
                         "Third-party context type IntContext must be propagated to thread " +
                         "per ManagedThreadFactoryDefinition and ContextServiceDefinition configuration " +
                         "based on the thread context at the time the ManagedThreadFactory was looked up.");

            Object lookupResult = results.poll(MAX_WAIT_SECONDS, TimeUnit.SECONDS);
            if (lookupResult instanceof Throwable)
                throw new AssertionError().initCause((Throwable) lookupResult);
            assertTrue(lookupResult instanceof ContextService,
                       "Application context must be propagated to thread " +
                       "per ManagedThreadFactoryDefinition and ContextServiceDefinition configuration.");
        } finally {
            IntContext.set(0);
            StringContext.set("");
        }

        return SUCCESS;
    }

    /**
     * ManagedThreadFactoryDefinition with minimal attributes creates threads with normal priority
     * and uses java:comp/DefaultContextService to determine context propagation and clearing.
     */
    private String testManagedThreadFactoryDefinitionDefaults() throws Throwable {
        ManagedThreadFactory threadFactory = InitialContext.doLookup("java:comp/concurrent/ThreadFactoryB");

        CountDownLatch blocker = new CountDownLatch(1);
        CountDownLatch allThreadsRunning = new CountDownLatch(2);
        CompletableFuture<Object> lookupTaskResult = new CompletableFuture<Object>();
        CompletableFuture<Object> txTaskResult = new CompletableFuture<Object>();

        Runnable lookupTask = () -> {
            try {
                allThreadsRunning.countDown();
                blocker.await(MAX_WAIT_SECONDS * 5, TimeUnit.SECONDS);
                lookupTaskResult.complete(InitialContext.doLookup("java:comp/concurrent/ContextC"));
            } catch (Throwable x) {
                txTaskResult.completeExceptionally(x);
            }
        };

        Runnable txTask = () -> {
            try {
                allThreadsRunning.countDown();
                UserTransaction tx = InitialContext.doLookup("java:comp/UserTransaction");
                int initialStatus = tx.getStatus();
                tx.begin();
                try {
                    blocker.await(MAX_WAIT_SECONDS * 5, TimeUnit.SECONDS);
                } finally {
                    tx.rollback();
                }
                txTaskResult.complete(initialStatus);
            } catch (Throwable x) {
                txTaskResult.completeExceptionally(x);
            }
        };

        try {
            threadFactory.newThread(lookupTask).start();
            threadFactory.newThread(txTask).start();

            assertTrue(allThreadsRunning.await(MAX_WAIT_SECONDS, TimeUnit.SECONDS),
                       "ManagedThreadFactory threads must start running.");

            blocker.countDown();

            Object result;
            if ((result = lookupTaskResult.get(MAX_WAIT_SECONDS, TimeUnit.SECONDS)) instanceof Throwable)
                throw new AssertionError().initCause((Throwable) result);
            assertTrue(result instanceof ContextService,
                       "Application context must be propagated to first thread " +
                       "per java:comp/concurrent/ThreadFactoryB configuration.");

            if ((result = txTaskResult.get(MAX_WAIT_SECONDS, TimeUnit.SECONDS)) instanceof Throwable)
                throw new AssertionError().initCause((Throwable) result);
            assertEquals(Integer.valueOf(Status.STATUS_NO_TRANSACTION), result,
                         "Transaction context must be cleared from async Callable task " +
                         "per java:comp/concurrent/ThreadFactoryB configuration.");
        } finally {
            IntContext.set(0);
            blocker.countDown();
        }

        return SUCCESS;
    }

    /**
     * ManagedThreadFactory can be supplied to a ForkJoinPool, causing ForkJoinPool tasks to run with the
     * thread context and priority as configured.
     */
    private String testParallelStreamBackedByManagedThreadFactory() throws Throwable {
        ForkJoinPool fj = null;
        try {
            IntContext.set(1000);
            StringContext.set("testParallelStreamBackedByManagedThreadFactory-1");

            ManagedThreadFactory threadFactory = InitialContext.doLookup("java:app/concurrent/ThreadFactoryA");

            IntContext.set(2000);
            StringContext.set("testParallelStreamBackedByManagedThreadFactory-2");

            fj = new ForkJoinPool(4, threadFactory, null, false);

            IntContext.set(3000);
            StringContext.set("testParallelStreamBackedByManagedThreadFactory-3");

            ForkJoinTask<Optional<Integer>> task = fj.submit(() -> {
                return Arrays.asList(1, 2, 3, 4, 5, 6, 7, 8, 9)
                                .parallelStream()
                                .map(num -> {
                                    assertEquals("", StringContext.get(),
                                                 "Third-party context type StringContext must be cleared on " +
                                                 "ForkJoin thread.");
                                    try {
                                        assertNotNull(InitialContext.doLookup("java:app/concurrent/ContextA"),
                                                      "Application context must be propagated to ForkJoin thread");
                                    } catch (NamingException x) {
                                        throw new CompletionException(x);
                                    }
                                    return num * Thread.currentThread().getPriority() + IntContext.get();
                                })
                                .reduce(Integer::sum);
            });

            Optional<Integer> result = task.join();
            assertEquals(Integer.valueOf(9180), result.get(),
                         "Third-party context type IntContext must propagated to ForkJoin threads " +
                         "(thousands digit should be 9) and thread priority (4) must be enforced " +
                         "on ForkJoin threads (hundreds/tens/ones digits must be 4x5x9=180) " +
                         "per configuration of the ManagedThreadFactoryDefinition and ContextServiceDefinition.");
        } finally {
            IntContext.set(0);
            StringContext.set(null);
            if (fj != null)
                fj.shutdown();
        }

        return SUCCESS;
    }
}