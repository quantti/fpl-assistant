package fi.karivanttinen.fplassistant.chat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.context.request.async.DeferredResult;
import org.springframework.web.context.request.async.DeferredResultProcessingInterceptor;
import org.springframework.web.servlet.AsyncHandlerInterceptor;
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitterReturnValueHandler;

/**
 * KAR-42 test-only probes around the chat SSE lifecycle. Every probe delegates to the real
 * object and only records or pauses (with a deadline) at a boundary read from Spring Framework
 * 7.0.8 / Spring Security 7.1.0 source; none removes a lock or changes which thread runs what.
 *
 * <ul>
 *   <li>{@link ProbeResponse}, installed by an outermost filter, sees every response-header
 *       mutation and {@code flushBuffer} (commit) from any thread — including the security
 *       headers {@code HeaderWriterFilter} writes on commit and in its {@code finally}.</li>
 *   <li>A {@code DeferredResultProcessingInterceptor}: {@code beforeConcurrentHandling} runs on
 *       the request thread before {@code ResponseBodyEmitter.initialize}; {@code postProcess}
 *       runs on the emitter thread after the terminal flush and before the async dispatch.</li>
 *   <li>{@code AsyncHandlerInterceptor.afterConcurrentHandlingStarted}: request thread, after
 *       {@code initialize} returned.</li>
 *   <li>The MVC async executor, wrapped in place: the real executor still runs every
 *       {@code ReactiveTypeHandler} send task; the wrapper only counts and may delay one.</li>
 * </ul>
 */
final class SseLifecycleProbe {

    static final String RUN_HEADER = "X-Kar42-Run";
    static final Duration PHASE_TIMEOUT = Duration.ofSeconds(5);
    static final Path DIAGNOSTICS = Path.of("build", "diagnostics", "kar-42");

    enum Ordering {
        /** Request thread waits before {@code initialize} until the first send task ran. */
        EARLY_QUEUED,
        /** First send task waits until {@code initialize} returned; it then commits while the
         *  request thread unwinds through the servlet and filter chain. */
        FIRST_FLUSH_DURING_UNWIND,
        /** Early-queued start; the terminal flush and completion are then observed against the
         *  async dispatch and the client's reading of the response. */
        TERMINAL_DURING_DISPATCH
    }

    private volatile Run current;

    Run arm(String id, Ordering ordering, boolean holdCompletionUntilClientSawDone) {
        Run run = new Run(id, ordering, holdCompletionUntilClientSawDone);
        this.current = run;
        return run;
    }

    void disarm() {
        this.current = null;
    }

    private Run runFor(HttpServletRequest request) {
        Run run = this.current;
        return run != null && run.id.equals(request.getHeader(RUN_HEADER)) ? run : null;
    }

    /** One case's recorded lifecycle. Sequence numbers order events across threads. */
    static final class Run {

        final String id;
        final Ordering ordering;
        final boolean holdCompletionUntilClientSawDone;

        private final AtomicInteger seq = new AtomicInteger();
        private final List<String> log = Collections.synchronizedList(new ArrayList<>());
        private final Map<String, Integer> firstSeq = new ConcurrentHashMap<>();
        private final Map<String, String> firstThread = new ConcurrentHashMap<>();
        final List<String> overlaps = Collections.synchronizedList(new ArrayList<>());
        final List<String> lateEmitterOps = Collections.synchronizedList(new ArrayList<>());
        final List<String> timeouts = Collections.synchronizedList(new ArrayList<>());

        final CountDownLatch requestExited = new CountDownLatch(1);
        final CountDownLatch emitterInitialized = new CountDownLatch(1);
        final CountDownLatch firstSendTaskDone = new CountDownLatch(1);
        final CountDownLatch emitterReachedResponse = new CountDownLatch(1);
        final CountDownLatch clientSawDone = new CountDownLatch(1);
        final AtomicInteger sendTasks = new AtomicInteger();

        volatile Thread requestThread;
        private volatile Thread emitterCommitting;
        private volatile Thread completionThread;

        private Run(String id, Ordering ordering, boolean holdCompletionUntilClientSawDone) {
            this.id = id;
            this.ordering = ordering;
            this.holdCompletionUntilClientSawDone = holdCompletionUntilClientSawDone;
        }

        int mark(String key, String detail) {
            int n = this.seq.incrementAndGet();
            String thread = Thread.currentThread().getName();
            this.firstSeq.putIfAbsent(key, n);
            this.firstThread.putIfAbsent(key, thread);
            this.log.add(n + " [" + thread + "] " + key + (detail.isEmpty() ? "" : " " + detail));
            return n;
        }

        /** Sequence number of the first event with this key, or -1 if it never happened. */
        int seq(String key) {
            return this.firstSeq.getOrDefault(key, -1);
        }

        String thread(String key) {
            return this.firstThread.get(key);
        }

        List<String> log() {
            synchronized (this.log) {
                return List.copyOf(this.log);
            }
        }

        private void hold(CountDownLatch until, String key) {
            mark(key + ".hold", "");
            boolean reached;
            try {
                reached = until.await(PHASE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                reached = false;
            }
            mark(key + (reached ? ".released" : ".timeout"), "");
            if (!reached) {
                this.timeouts.add(key);
            }
        }

        /** Teardown: nothing may stay paused, whatever the test's outcome. */
        void releaseAll() {
            for (CountDownLatch latch : List.of(this.requestExited, this.emitterInitialized,
                    this.firstSendTaskDone, this.emitterReachedResponse, this.clientSawDone)) {
                while (latch.getCount() > 0) {
                    latch.countDown();
                }
            }
        }

        private void beforeResponseMutation(String op) {
            Thread thread = Thread.currentThread();
            boolean dispatching = this.requestExited.getCount() > 0;
            if (thread == this.completionThread) {
                this.lateEmitterOps.add(op);
            }
            if (thread == this.requestThread && dispatching) {
                Thread emitter = this.emitterCommitting;
                if (emitter != null) {
                    this.overlaps.add("request thread [" + thread.getName() + "] " + op
                            + " while emitter thread [" + emitter.getName()
                            + "] was inside its first commit's header writes");
                }
                mark("request.mutation", op);
            }
            else if (dispatching) {
                mark("emitter.mutation-during-dispatch", op);
                mark("emitter.reached-response", op);
                if (this.emitterCommitting == null) {
                    this.emitterCommitting = thread;
                    this.emitterReachedResponse.countDown();
                    if (this.ordering == Ordering.FIRST_FLUSH_DURING_UNWIND) {
                        // Paused before delegating: the request thread's own writes proceed
                        // alone, so what is recorded is the overlap, never a manufactured CME.
                        hold(this.requestExited, "emitter.first-header-write");
                    }
                }
            }
            else {
                mark("mutation-after-dispatch", op);
            }
        }

        private void onFlushBuffer() {
            Thread thread = Thread.currentThread();
            if (thread == this.completionThread) {
                this.lateEmitterOps.add("flushBuffer");
            }
            boolean requestThread = thread == this.requestThread && this.requestExited.getCount() > 0;
            mark("commit", requestThread ? "flushBuffer on the request thread" : "flushBuffer");
            if (!requestThread && this.requestExited.getCount() > 0) {
                mark("emitter.reached-response", "flushBuffer");
                this.emitterReachedResponse.countDown();
            }
        }
    }

    /** Records every header mutation and commit, from whichever thread makes it. */
    static final class ProbeResponse extends HttpServletResponseWrapper {

        private final Run run;

        ProbeResponse(HttpServletResponse response, Run run) {
            super(response);
            this.run = run;
        }

        @Override
        public void setHeader(String name, String value) {
            this.run.beforeResponseMutation("setHeader " + name);
            super.setHeader(name, value);
        }

        @Override
        public void addHeader(String name, String value) {
            this.run.beforeResponseMutation("addHeader " + name);
            super.addHeader(name, value);
        }

        @Override
        public void setIntHeader(String name, int value) {
            this.run.beforeResponseMutation("setIntHeader " + name);
            super.setIntHeader(name, value);
        }

        @Override
        public void addIntHeader(String name, int value) {
            this.run.beforeResponseMutation("addIntHeader " + name);
            super.addIntHeader(name, value);
        }

        @Override
        public void setDateHeader(String name, long date) {
            this.run.beforeResponseMutation("setDateHeader " + name);
            super.setDateHeader(name, date);
        }

        @Override
        public void addDateHeader(String name, long date) {
            this.run.beforeResponseMutation("addDateHeader " + name);
            super.addDateHeader(name, date);
        }

        @Override
        public void setContentType(String type) {
            this.run.beforeResponseMutation("setContentType");
            super.setContentType(type);
        }

        @Override
        public void setCharacterEncoding(String charset) {
            this.run.beforeResponseMutation("setCharacterEncoding");
            super.setCharacterEncoding(charset);
        }

        @Override
        public void setStatus(int sc) {
            this.run.beforeResponseMutation("setStatus");
            super.setStatus(sc);
        }

        @Override
        public void flushBuffer() throws IOException {
            this.run.onFlushBuffer();
            super.flushBuffer();
        }
    }

    /** Delegates every task to the real MVC executor; only counts and may delay a send task. */
    static final class ProbeExecutor implements AsyncTaskExecutor {

        private final AsyncTaskExecutor delegate;
        private final ObjectProvider<SseLifecycleProbe> probe;

        ProbeExecutor(AsyncTaskExecutor delegate, ObjectProvider<SseLifecycleProbe> probe) {
            this.delegate = delegate;
            this.probe = probe;
        }

        @Override
        public void execute(Runnable task) {
            this.delegate.execute(probed(task));
        }

        @Override
        public Future<?> submit(Runnable task) {
            return this.delegate.submit(probed(task));
        }

        @Override
        public <T> Future<T> submit(Callable<T> task) {
            return this.delegate.submit(task);
        }

        private Runnable probed(Runnable task) {
            Run run = this.probe.getObject().current;
            if (run == null) {
                return task;
            }
            return () -> {
                int n = run.sendTasks.incrementAndGet();
                run.mark("send-task-" + n + ".start", "");
                if (n == 1 && run.ordering == Ordering.FIRST_FLUSH_DURING_UNWIND) {
                    run.hold(run.emitterInitialized, "send-task-1");
                }
                try {
                    task.run();
                }
                finally {
                    run.mark("send-task-" + n + ".end", "");
                    if (n == 1) {
                        run.firstSendTaskDone.countDown();
                    }
                }
            };
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Config {

        @Bean
        static SseLifecycleProbe sseLifecycleProbe() {
            return new SseLifecycleProbe();
        }

        /**
         * Wraps the adapter's executor before {@code afterPropertiesSet} builds the return-value
         * handlers, so {@code ReactiveTypeHandler} receives the wrapper around the executor Boot
         * configured. Also records that executor (Ruling A) and, after initialization, which
         * executor the reactive handler actually holds.
         */
        @Bean
        static BeanPostProcessor kar42ExecutorProbe(ObjectProvider<SseLifecycleProbe> probe,
                                                    Environment environment) {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessBeforeInitialization(Object bean, String beanName) {
                    if (bean instanceof RequestMappingHandlerAdapter adapter) {
                        AsyncTaskExecutor original =
                                (AsyncTaskExecutor) ReflectionTestUtils.getField(adapter, "taskExecutor");
                        writeDiagnostic("mvc-async-executor.txt", describe(original, adapter, environment));
                        adapter.setTaskExecutor(new ProbeExecutor(original, probe));
                    }
                    return bean;
                }

                @Override
                public Object postProcessAfterInitialization(Object bean, String beanName) {
                    if (bean instanceof RequestMappingHandlerAdapter adapter
                            && adapter.getReturnValueHandlers() != null) {
                        adapter.getReturnValueHandlers().stream()
                                .filter(ResponseBodyEmitterReturnValueHandler.class::isInstance)
                                .findFirst()
                                .ifPresent(handler -> {
                                    Object reactive = ReflectionTestUtils.getField(handler, "reactiveHandler");
                                    Object executor = ReflectionTestUtils.getField(reactive, "taskExecutor");
                                    appendDiagnostic("mvc-async-executor.txt",
                                            "ReactiveTypeHandler executor after init: "
                                                    + executor.getClass().getName() + "\n");
                                });
                    }
                    return bean;
                }
            };
        }

        @Bean
        static FilterRegistrationBean<Filter> kar42ProbeFilter(SseLifecycleProbe probe) {
            Filter filter = (request, response, chain) -> {
                HttpServletRequest http = (HttpServletRequest) request;
                Run run = probe.runFor(http);
                if (run == null) {
                    chain.doFilter(request, response);
                    return;
                }
                if (http.getDispatcherType() == DispatcherType.REQUEST) {
                    run.requestThread = Thread.currentThread();
                    run.mark("request.enter", "");
                    try {
                        chain.doFilter(request, new ProbeResponse((HttpServletResponse) response, run));
                    }
                    finally {
                        run.mark("request.exit", "");
                        run.requestExited.countDown();
                    }
                }
                else {
                    String type = http.getDispatcherType().name().toLowerCase();
                    run.mark(type + ".enter", "");
                    try {
                        chain.doFilter(request, response);
                    }
                    finally {
                        run.mark(type + ".exit", "");
                    }
                }
            };
            FilterRegistrationBean<Filter> registration = new FilterRegistrationBean<>(filter);
            registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
            registration.setDispatcherTypes(DispatcherType.REQUEST, DispatcherType.ASYNC);
            registration.addUrlPatterns("/api/chat");
            return registration;
        }

        @Bean
        static WebMvcConfigurer kar42ProbeMvc(SseLifecycleProbe probe) {
            return new WebMvcConfigurer() {
                @Override
                public void addInterceptors(InterceptorRegistry registry) {
                    registry.addInterceptor(new AsyncHandlerInterceptor() {
                        @Override
                        public void afterConcurrentHandlingStarted(HttpServletRequest request,
                                                                   HttpServletResponse response,
                                                                   Object handler) {
                            Run run = probe.runFor(request);
                            if (run == null) {
                                return;
                            }
                            run.mark("emitter.initialized", "");
                            run.emitterInitialized.countDown();
                            if (run.ordering == Ordering.FIRST_FLUSH_DURING_UNWIND) {
                                run.hold(run.emitterReachedResponse, "request.unwind");
                            }
                        }
                    }).addPathPatterns("/api/chat");
                }

                @Override
                public void configureAsyncSupport(AsyncSupportConfigurer configurer) {
                    configurer.registerDeferredResultInterceptors(new DeferredResultProcessingInterceptor() {
                        @Override
                        public <T> void beforeConcurrentHandling(NativeWebRequest request,
                                                                 DeferredResult<T> deferredResult) {
                            Run run = runOf(request);
                            if (run == null) {
                                return;
                            }
                            run.mark("before-initialize", "");
                            if (run.ordering != Ordering.FIRST_FLUSH_DURING_UNWIND) {
                                run.hold(run.firstSendTaskDone, "before-initialize");
                            }
                        }

                        @Override
                        public <T> void postProcess(NativeWebRequest request,
                                                    DeferredResult<T> deferredResult,
                                                    Object concurrentResult) {
                            Run run = runOf(request);
                            if (run == null) {
                                return;
                            }
                            run.completionThread = Thread.currentThread();
                            run.mark("result-set.before-async-dispatch", "");
                            if (run.ordering == Ordering.TERMINAL_DURING_DISPATCH
                                    && run.holdCompletionUntilClientSawDone) {
                                run.hold(run.clientSawDone, "completion");
                            }
                        }

                        private Run runOf(NativeWebRequest request) {
                            HttpServletRequest http = request.getNativeRequest(HttpServletRequest.class);
                            return http == null ? null : probe.runFor(http);
                        }
                    });
                }
            };
        }

        private static String describe(AsyncTaskExecutor executor, RequestMappingHandlerAdapter adapter,
                                       Environment environment) {
            StringBuilder out = new StringBuilder("MVC async task executor (RequestMappingHandlerAdapter)\n");
            out.append("type: ").append(executor.getClass().getName()).append('\n');
            if (executor instanceof ThreadPoolTaskExecutor pool) {
                out.append("corePoolSize: ").append(pool.getCorePoolSize()).append('\n')
                        .append("maxPoolSize: ").append(pool.getMaxPoolSize()).append('\n')
                        .append("queueCapacity: ").append(pool.getQueueCapacity()).append('\n')
                        .append("keepAliveSeconds: ").append(pool.getKeepAliveSeconds()).append('\n')
                        .append("threadNamePrefix: ").append(pool.getThreadNamePrefix()).append('\n');
            }
            else if (executor instanceof SimpleAsyncTaskExecutor simple) {
                out.append("concurrencyLimit: ").append(simple.getConcurrencyLimit()).append('\n')
                        .append("threadNamePrefix: ").append(simple.getThreadNamePrefix()).append('\n');
            }
            out.append("asyncRequestTimeout: ")
                    .append(ReflectionTestUtils.getField(adapter, "asyncRequestTimeout")).append('\n');
            out.append("spring.threads.virtual.enabled: ")
                    .append(environment.getProperty("spring.threads.virtual.enabled", "(unset)")).append('\n');
            out.append("spring.mvc.async.request-timeout: ")
                    .append(environment.getProperty("spring.mvc.async.request-timeout", "(unset)")).append('\n');
            return out.toString();
        }
    }

    static void writeDiagnostic(String name, String content) {
        try {
            Files.createDirectories(DIAGNOSTICS.resolve(name).getParent());
            Files.writeString(DIAGNOSTICS.resolve(name), content);
        }
        catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void appendDiagnostic(String name, String content) {
        try {
            Files.writeString(DIAGNOSTICS.resolve(name), content, StandardOpenOption.APPEND);
        }
        catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
