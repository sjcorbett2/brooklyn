package brooklyn.entity.webapp;

import static brooklyn.test.HttpTestUtils.connectToUrl;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertTrue;

import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketException;
import java.net.URL;
import java.net.URLConnection;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import brooklyn.entity.Application;
import brooklyn.entity.basic.ApplicationBuilder;
import brooklyn.entity.basic.Entities;
import brooklyn.entity.basic.EntityInternal;
import brooklyn.entity.basic.EntityLocal;
import brooklyn.entity.basic.SoftwareProcess;
import brooklyn.entity.proxying.EntitySpecs;
import brooklyn.entity.rebind.dto.MementosGenerators;
import brooklyn.entity.trait.Startable;
import brooklyn.entity.webapp.jboss.JBoss6Server;
import brooklyn.entity.webapp.jboss.JBoss7Server;
import brooklyn.entity.webapp.tomcat.TomcatServer;
import brooklyn.event.SensorEvent;
import brooklyn.event.SensorEventListener;
import brooklyn.location.basic.LocalhostMachineProvisioningLocation;
import brooklyn.location.basic.PortRanges;
import brooklyn.management.ManagementContext;
import brooklyn.management.SubscriptionContext;
import brooklyn.management.SubscriptionHandle;
import brooklyn.management.internal.ManagementContextInternal;
import brooklyn.mementos.BrooklynMemento;
import brooklyn.test.Asserts;
import brooklyn.test.HttpTestUtils;
import brooklyn.test.entity.TestApplication;
import brooklyn.util.collections.MutableMap;
import brooklyn.util.internal.Repeater;
import brooklyn.util.time.Time;

import com.google.common.base.Stopwatch;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;

/**
 * Tests that implementations of JavaWebApp can start up and shutdown, 
 * post request and error count metrics and deploy wars, etc.
 * 
 * Currently tests {@link TomcatServer}, {@link JBoss6Server} and {@link JBoss7Server}.
 */
public class WebAppIntegrationTest {
    
    private static final Logger log = LoggerFactory.getLogger(WebAppIntegrationTest.class);
    
    // Don't use 8080 since that is commonly used by testing software
    public static final String DEFAULT_HTTP_PORT = "7880+";
    
    // Port increment for JBoss 6.
    public static final int PORT_INCREMENT = 400;

    // The parent application entity for these tests
    private List<TestApplication> applications = Lists.newArrayList();
    private SoftwareProcess entity;
    private LocalhostMachineProvisioningLocation loc;

    @BeforeMethod(alwaysRun=true)
    public void setUp() throws Exception {
        loc = new LocalhostMachineProvisioningLocation(MutableMap.of("name", "london"));
    }
    
    /*
     * Use of @DataProvider with test methods gives surprising behaviour with @AfterMethod.
     * Unless careful, this causes problems when trying to ensure everything is shutdown cleanly.
     *
     * Empirically, the rules seem to be...
     *  - @DataProvider method is called first; it creates a bunch of cases to run 
     *    (all sharing the same instance of WebAppIntegrationTest).
     *  - It runs the test method for the first time with the first @DataProvider value
     *  - It runs @AfterMethod
     *  - It runs the test method for the second @DataProvider value
     *  - It runs @AfterMethod
     *  - etc...
     *
     * Previously shutdownApp was calling stop on each app in applications, and clearing the applications set;
     * but then the second invocation of the method was starting an entity that was never stopped. Until recently,
     * every test method was also terminating the entity (belt-and-braces, but also brittle for if the method threw
     * an exception earlier). When that "extra" termination was removed, it meant the second and subsequent 
     * entities were never being stopped.
     *
     * Now we rely on having the test method set the entity field, so we can find out which application instance 
     * it is and calling stop on just that app + entity.
     */
    @AfterMethod(alwaysRun=true)
    public void shutdownApp() {
        if (entity != null) {
            Application app = entity.getApplication();
            if (app != null) Entities.destroyAll(app);
        }
    }

    @AfterMethod(alwaysRun=true, dependsOnMethods="shutdownApp")
    public void ensureTomcatIsShutDown() throws Exception {
        final AtomicReference<Socket> shutdownSocket = new AtomicReference<Socket>();
        final AtomicReference<SocketException> gotException = new AtomicReference<SocketException>();
        final Integer shutdownPort = (entity != null) ? entity.getAttribute(TomcatServer.SHUTDOWN_PORT) : null;
        
        if (shutdownPort != null) {
            boolean socketClosed = Repeater.create("Checking Tomcat has shut down")
                    .repeat(new Callable<Void>() {
                            public Void call() throws Exception {
                                if (shutdownSocket.get() != null) shutdownSocket.get().close();
                                try {
                                    shutdownSocket.set(new Socket(InetAddress.getLocalHost(), shutdownPort));
                                    gotException.set(null);
                                } catch (SocketException e) {
                                    gotException.set(e);
                                }
                                return null;
                            }})
                    .every(100, TimeUnit.MILLISECONDS)
                    .until(new Callable<Boolean>() {
                            public Boolean call() {
                                return (gotException.get() != null);
                            }})
                    .limitIterationsTo(25)
                    .run();
            
            if (socketClosed == false) {
//                log.error("Tomcat did not shut down - this is a failure of the last test run");
//                log.warn("I'm sending a message to the Tomcat shutdown port {}", shutdownPort);
//                OutputStreamWriter writer = new OutputStreamWriter(shutdownSocket.getOutputStream());
//                writer.write("SHUTDOWN\r\n");
//                writer.flush();
//                writer.close();
//                shutdownSocket.close();
                throw new Exception("Last test run did not shut down Tomcat entity "+entity+" (port "+shutdownPort+")");
            }
        } else {
            log.info("Cannot shutdown, because shutdown-port not set for {}", entity);
        }
    }

    /** 
     * Create a new instance of TestApplication and append it to applications list
     * so it can be terminated suitable after each test has run.
     * @return
     */
    private TestApplication newTestApplication() {
        TestApplication ta = ApplicationBuilder.newManagedApp(TestApplication.class);
        applications.add(ta);
        return ta;
    }

    /**
     * Provides instances of {@link TomcatServer}, {@link JBoss6Server} and {@link JBoss7Server} to the tests below.
     *
     * TODO combine the data provider here with live integration test
     *
     * @see WebAppLiveIntegrationTest#basicEntities()
     */
    @DataProvider(name = "basicEntities")
    public JavaWebAppSoftwareProcess[][] basicEntities() {
		//FIXME we should start the application, not the entity
        TestApplication tomcatApp = newTestApplication();
        TomcatServer tomcat = tomcatApp.createAndManageChild(EntitySpecs.spec(TomcatServer.class)
                .configure(TomcatServer.HTTP_PORT, PortRanges.fromString(DEFAULT_HTTP_PORT)));
        
        TestApplication jboss6App = newTestApplication();
        JBoss6Server jboss6 = jboss6App.createAndManageChild(EntitySpecs.spec(JBoss6Server.class)
                .configure(JBoss6Server.PORT_INCREMENT, PORT_INCREMENT));
        
        TestApplication jboss7App = newTestApplication();
        JBoss7Server jboss7 = jboss7App.createAndManageChild(EntitySpecs.spec(JBoss7Server.class)
                .configure(JBoss7Server.HTTP_PORT, PortRanges.fromString(DEFAULT_HTTP_PORT)));
        
        return new JavaWebAppSoftwareProcess[][] {
                new JavaWebAppSoftwareProcess[] {tomcat}, 
                new JavaWebAppSoftwareProcess[] {jboss6}, 
                new JavaWebAppSoftwareProcess[] {jboss7}};
    }

    /**
     * Checks an entity can start, set SERVICE_UP to true and shutdown again.
     */
    @Test(groups = "Integration", dataProvider = "basicEntities")
    public void canStartAndStop(final SoftwareProcess entity) {
        this.entity = entity;
        log.info("test=canStartAndStop; entity="+entity+"; app="+entity.getApplication());
        
        entity.start(ImmutableList.of(loc));
        Asserts.succeedsEventually(MutableMap.of("timeout", 120*1000), new Runnable() {
            public void run() {
                assertTrue(entity.getAttribute(Startable.SERVICE_UP));
            }});
        
        entity.stop();
        assertFalse(entity.getAttribute(Startable.SERVICE_UP));
    }
    
    /**
     * Checks an entity can start, set SERVICE_UP to true and shutdown again.
     */
    @Test(groups = "Integration", dataProvider = "basicEntities")
    public void testReportsServiceDownWhenKilled(final SoftwareProcess entity) {
        this.entity = entity;
        log.info("test=testReportsServiceDownWithKilled; entity="+entity+"; app="+entity.getApplication());
        
        entity.start(ImmutableList.of(loc));
        Asserts.succeedsEventually(MutableMap.of("timeout", 120*1000), new Runnable() {
            public void run() {
                assertTrue(entity.getAttribute(Startable.SERVICE_UP));
            }});

        // Stop the underlying entity, but without our entity instance being told!
        // Previously was calling entity.getDriver().kill(); but now our entity instance is a proxy so can't do that
        ManagementContext newManagementContext = null;
        try {
            ManagementContext managementContext = ((EntityInternal)entity).getManagementContext();
            BrooklynMemento brooklynMemento = MementosGenerators.newBrooklynMemento(managementContext);
            
            newManagementContext = Entities.newManagementContext();
            newManagementContext.getRebindManager().rebind(brooklynMemento, WebAppIntegrationTest.class.getClassLoader());
            SoftwareProcess entity2 = (SoftwareProcess) newManagementContext.getEntityManager().getEntity(entity.getId());
            entity2.stop();
        } finally {
            if (newManagementContext != null) ((ManagementContextInternal)newManagementContext).terminate();
        }

        Asserts.succeedsEventually(new Runnable() {
            public void run() {
                assertFalse(entity.getAttribute(Startable.SERVICE_UP));
            }});
    }
    
    /**
     * Checks that an entity correctly sets request and error count metrics by
     * connecting to a non-existent URL several times.
     */
    @Test(groups = "Integration", dataProvider = "basicEntities")
    public void publishesRequestAndErrorCountMetrics(final SoftwareProcess entity) throws Exception {
        this.entity = entity;
        log.info("test=publishesRequestAndErrorCountMetrics; entity="+entity+"; app="+entity.getApplication());
        
        entity.start(ImmutableList.of(loc));
        
        Asserts.succeedsEventually(MutableMap.of("timeout", 10*1000), new Runnable() {
            public void run() {
                assertTrue(entity.getAttribute(SoftwareProcess.SERVICE_UP));
            }});
        
        String url = entity.getAttribute(WebAppService.ROOT_URL) + "does_not_exist";
        
        final int n = 10;
        for (int i = 0; i < n; i++) {
            URLConnection connection = HttpTestUtils.connectToUrl(url);
            int status = ((HttpURLConnection) connection).getResponseCode();
            log.info("connection to {} gives {}", url, status);
        }
        
        Asserts.succeedsEventually(MutableMap.of("timeout", 20*1000), new Runnable() {
            public void run() {
                Integer requestCount = entity.getAttribute(WebAppService.REQUEST_COUNT);
                Integer errorCount = entity.getAttribute(WebAppService.ERROR_COUNT);
                log.info("req={}, err={}", requestCount, errorCount);
                
                assertNotNull(errorCount, "errorCount not set yet ("+errorCount+")");
    
                // AS 7 seems to take a very long time to report error counts,
                // hence not using ==.  >= in case error pages include a favicon, etc.
                assertEquals(errorCount, (Integer)n);
                assertTrue(requestCount >= errorCount);
            }});
    }
    
    /**
     * Checks an entity publishes correct requests/second figures and that these figures
     * fall to zero after a period of no activity.
     */
    @Test(groups = "Integration", dataProvider = "basicEntities")
    public void publishesRequestsPerSecondMetric(final SoftwareProcess entity) throws Exception {
        this.entity = entity;
        log.info("test=publishesRequestsPerSecondMetric; entity="+entity+"; app="+entity.getApplication());
        
        entity.start(ImmutableList.of(loc));
        log.info("Entity "+entity+" started");
        
        try {
            // reqs/sec initially zero
            log.info("Waiting for initial avg-requests to be zero...");
            Asserts.succeedsEventually(MutableMap.of("timeout", 20*1000), new Runnable() {
                public void run() {
                    Double activityValue = entity.getAttribute(WebAppService.AVG_REQUESTS_PER_SECOND);
                    assertNotNull(activityValue, "activity not set yet "+activityValue+")");
                    assertEquals(activityValue.doubleValue(), 0.0d, 0.000001d);
                }});
            
            // apply workload on 1 per sec; reqs/sec should update
            Asserts.succeedsEventually(MutableMap.of("timeout", 30*1000), new Callable<Void>() {
                public Void call() throws Exception {
                    String url = entity.getAttribute(WebAppService.ROOT_URL) + "does_not_exist";
                    final int desiredMsgsPerSec = 10;
                    
                    Stopwatch stopwatch = new Stopwatch().start();
                    final AtomicInteger reqsSent = new AtomicInteger();
                    final Integer preRequestCount = entity.getAttribute(WebAppService.REQUEST_COUNT);
                    
                    // need to maintain n requests per second for the duration of the window size
                    log.info("Applying load for "+WebAppService.REQUESTS_PER_SECOND_WINDOW_PERIOD+"ms");
                    while (stopwatch.elapsed(TimeUnit.MILLISECONDS) < WebAppService.REQUESTS_PER_SECOND_WINDOW_PERIOD) {
                        long preReqsTime = stopwatch.elapsed(TimeUnit.MILLISECONDS);
                        for (int i = 0; i < desiredMsgsPerSec; i++) { connectToUrl(url); }
                        sleep(1000 - (stopwatch.elapsed(TimeUnit.MILLISECONDS)-preReqsTime));
                        reqsSent.addAndGet(desiredMsgsPerSec);
                    }
    
                    Asserts.succeedsEventually(MutableMap.of("timeout", 1000), new Runnable() {
                        public void run() {
                            Double avgReqs = entity.getAttribute(WebAppService.REQUESTS_PER_SECOND_IN_WINDOW);
                            Integer requestCount = entity.getAttribute(WebAppService.REQUEST_COUNT);
                            
                            log.info("avg-requests="+avgReqs+"; total-requests="+requestCount);
                            assertEquals(avgReqs.doubleValue(), (double)desiredMsgsPerSec, 3.0d);
                            assertEquals(requestCount.intValue(), preRequestCount+reqsSent.get());
                        }});
                    
                    return null;
                }});
            
            // After suitable delay, expect to again get zero msgs/sec
            log.info("Waiting for avg-requests to drop to zero, for "+WebAppService.REQUESTS_PER_SECOND_WINDOW_PERIOD+"ms");
            Thread.sleep(WebAppService.REQUESTS_PER_SECOND_WINDOW_PERIOD);
            
            Asserts.succeedsEventually(MutableMap.of("timeout", 10*1000), new Runnable() {
                public void run() {
                    Double avgReqs = entity.getAttribute(WebAppService.REQUESTS_PER_SECOND_IN_WINDOW);
                    assertNotNull(avgReqs);
                    assertEquals(avgReqs.doubleValue(), 0.0d, 0.00001d);
                }});
        } finally {
            entity.stop();
        }
    }

    /**
     * Tests that we get consecutive events with zero workrate, and with suitably small timestamps between them.
     */
    @Test(groups = "Integration", dataProvider = "basicEntities")
    public void publishesZeroRequestsPerSecondMetricRepeatedly(final SoftwareProcess entity) {
        this.entity = entity;
        log.info("test=publishesZeroRequestsPerSecondMetricRepeatedly; entity="+entity+"; app="+entity.getApplication());
        
        final int MAX_INTERVAL_BETWEEN_EVENTS = 1000; // events should publish every 500ms so this should be enough overhead
        final int NUM_CONSECUTIVE_EVENTS = 3;

        entity.start(ImmutableList.of(loc));
        
        SubscriptionHandle subscriptionHandle = null;
        SubscriptionContext subContext = ((EntityInternal)entity).getSubscriptionContext();

        try {
            final List<SensorEvent> events = new CopyOnWriteArrayList<SensorEvent>();
            subscriptionHandle = subContext.subscribe(entity, WebAppService.REQUESTS_PER_SECOND_IN_WINDOW, new SensorEventListener<Double>() {
                public void onEvent(SensorEvent<Double> event) {
                    log.info("publishesRequestsPerSecondMetricRepeatedly.onEvent: {}", event);
                    events.add(event);
                }});
            
            
            Asserts.succeedsEventually(new Runnable() {
                public void run() {
                    assertTrue(events.size() > NUM_CONSECUTIVE_EVENTS, "events "+events.size()+" > "+NUM_CONSECUTIVE_EVENTS);
                    long eventTime = 0;
                    
                    for (SensorEvent event : events.subList(events.size()-NUM_CONSECUTIVE_EVENTS, events.size())) {
                        assertEquals(event.getSource(), entity);
                        assertEquals(event.getSensor(), WebAppService.AVG_REQUESTS_PER_SECOND);
                        assertEquals(event.getValue(), 0.0d);
                        if (eventTime > 0) assertTrue(event.getTimestamp()-eventTime < MAX_INTERVAL_BETWEEN_EVENTS,
    						"events at "+eventTime+" and "+event.getTimestamp()+" exceeded maximum allowable interval "+MAX_INTERVAL_BETWEEN_EVENTS);
                        eventTime = event.getTimestamp();
                    }
                }});
        } finally {
            if (subscriptionHandle != null) subContext.unsubscribe(subscriptionHandle);
            entity.stop();
        }
    }

    /**
     * Twins the entities given by basicEntities() with links to WAR files
     * they should be able to deploy.  Correct deployment can be checked by
     * pinging the given URL.
     *
     * <ul>
     * <li>Everything can deploy hello world
     * <li>Tomcat can deploy Spring travel
     * <li>JBoss can deploy Seam travel
     * </ul>
     */
    @DataProvider(name = "entitiesWithWarAndURL")
    public Object[][] entitiesWithWar() {
        List<Object[]> result = Lists.newArrayList();
        
        for (JavaWebAppSoftwareProcess[] entity : basicEntities()) {
            result.add(new Object[] {
                    entity[0],
                    "hello-world.war",
                    "hello-world/",
                    "" // no sub-page path
                    });
        }
        
        TestApplication tomcatApp = newTestApplication();
        TomcatServer tomcat = tomcatApp.createAndManageChild(EntitySpecs.spec(TomcatServer.class)
                .configure(TomcatServer.HTTP_PORT, PortRanges.fromString(DEFAULT_HTTP_PORT)));
        result.add(new Object[] {
                tomcat,
                "swf-booking-mvc.war",
                "swf-booking-mvc/",
				"spring/intro",
               });
            // FIXME seam-booking does not work
//            [   new JBoss6ServerImpl(parent:application, portIncrement:PORT_INCREMENT),
//				"seam-booking-as6.war",
//                "seam-booking-as6/",
//            ],
//            [   new JBoss7ServerImpl(parent:application, httpPort:DEFAULT_HTTP_PORT),
//                "seam-booking-as7.war",
//                "seam-booking-as7/",
//            ],
        
        return result.toArray(new Object[][] {});
    }

    /**
     * Tests given entity can deploy the given war.  Checks given httpURL to confirm success.
     */
    @Test(groups = "Integration", dataProvider = "entitiesWithWarAndURL")
    public void initialRootWarDeployments(final SoftwareProcess entity, final String war, 
			final String urlSubPathToWebApp, final String urlSubPathToPageToQuery) {
        this.entity = entity;
        log.info("test=initialRootWarDeployments; entity="+entity+"; app="+entity.getApplication());
        
        URL resource = getClass().getClassLoader().getResource(war);
        assertNotNull(resource);
        
        ((EntityLocal)entity).setConfig(JavaWebAppService.ROOT_WAR, resource.getPath());
        entity.start(ImmutableList.of(loc));
        
		//tomcat may need a while to unpack everything
        Asserts.succeedsEventually(MutableMap.of("timeout", 60*1000), new Runnable() {
            public void run() {
                // TODO get this URL from a WAR file entity
                HttpTestUtils.assertHttpStatusCodeEquals(entity.getAttribute(WebAppService.ROOT_URL)+urlSubPathToPageToQuery, 200);
                
                assertEquals(entity.getAttribute(JavaWebAppSoftwareProcess.DEPLOYED_WARS), ImmutableSet.of("/"));
            }});
    }
	
    @Test(groups = "Integration", dataProvider = "entitiesWithWarAndURL")
    public void initialNamedWarDeployments(final SoftwareProcess entity, final String war, 
			final String urlSubPathToWebApp, final String urlSubPathToPageToQuery) {
        this.entity = entity;
        log.info("test=initialNamedWarDeployments; entity="+entity+"; app="+entity.getApplication());
        
        URL resource = getClass().getClassLoader().getResource(war);
        assertNotNull(resource);
        
        ((EntityLocal)entity).setConfig(JavaWebAppService.NAMED_WARS, ImmutableList.of(resource.getPath()));
        entity.start(ImmutableList.of(loc));
        Asserts.succeedsEventually(MutableMap.of("timeout", 60*1000), new Runnable() {
            public void run() {
                // TODO get this URL from a WAR file entity
                HttpTestUtils.assertHttpStatusCodeEquals(entity.getAttribute(WebAppService.ROOT_URL)+urlSubPathToWebApp+urlSubPathToPageToQuery, 200);
            }});
    }
	
    @Test(groups = "Integration", dataProvider = "entitiesWithWarAndURL")
    public void testWarDeployAndUndeploy(final JavaWebAppSoftwareProcess entity, final String war, 
            final String urlSubPathToWebApp, final String urlSubPathToPageToQuery) {
        this.entity = entity;
        log.info("test=testWarDeployAndUndeploy; entity="+entity+"; app="+entity.getApplication());
        
        URL resource = getClass().getClassLoader().getResource(war);;
        assertNotNull(resource);
        
        entity.start(ImmutableList.of(loc));
        
        // Test deploying
        entity.deploy(resource.getPath(), "myartifactname.war");
        Asserts.succeedsEventually(MutableMap.of("timeout", 60*1000), new Runnable() {
            public void run() {
                // TODO get this URL from a WAR file entity
                HttpTestUtils.assertHttpStatusCodeEquals(entity.getAttribute(WebAppService.ROOT_URL)+"myartifactname/"+urlSubPathToPageToQuery, 200);
                assertEquals(entity.getAttribute(JavaWebAppSoftwareProcess.DEPLOYED_WARS), ImmutableSet.of("/myartifactname"));
            }});
        
        // And undeploying
        entity.undeploy("/myartifactname");
        Asserts.succeedsEventually(MutableMap.of("timeout", 60*1000), new Runnable() {
            public void run() {
                // TODO get this URL from a WAR file entity
                HttpTestUtils.assertHttpStatusCodeEquals(entity.getAttribute(WebAppService.ROOT_URL)+"myartifactname"+urlSubPathToPageToQuery, 404);
                assertEquals(entity.getAttribute(JavaWebAppSoftwareProcess.DEPLOYED_WARS), ImmutableSet.of());
            }});
    }
    
	public static void main(String ...args) {
		WebAppIntegrationTest t = new WebAppIntegrationTest();
		t.canStartAndStop(null);
	}
	
    private void sleep(long millis) {
        if (millis > 0) Time.sleep(millis);
    }    
}
