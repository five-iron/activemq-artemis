/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.activemq.artemis.core.server.federation;

import java.lang.invoke.MethodHandles;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.activemq.artemis.api.core.ActiveMQException;
import org.apache.activemq.artemis.api.core.ActiveMQNonExistentQueueException;
import org.apache.activemq.artemis.api.core.Message;
import org.apache.activemq.artemis.api.core.client.ClientConsumer;
import org.apache.activemq.artemis.api.core.client.ClientMessage;
import org.apache.activemq.artemis.api.core.client.ClientSession;
import org.apache.activemq.artemis.api.core.client.SessionFailureListener;
import org.apache.activemq.artemis.core.client.impl.ClientLargeMessageInternal;
import org.apache.activemq.artemis.core.client.impl.ClientSessionFactoryInternal;
import org.apache.activemq.artemis.core.persistence.StorageManager;
import org.apache.activemq.artemis.core.server.ActiveMQServer;
import org.apache.activemq.artemis.core.server.ActiveMQServerLogger;
import org.apache.activemq.artemis.core.server.LargeServerMessage;
import org.apache.activemq.artemis.core.server.transformer.Transformer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.apache.activemq.artemis.core.client.impl.LargeMessageControllerImpl.LargeData;

public class FederatedQueueConsumerImpl implements FederatedQueueConsumer, SessionFailureListener {

   private static final Logger logger = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

   private final ActiveMQServer server;
   private final Federation federation;
   private final FederatedConsumerKey key;
   private final Transformer transformer;
   private final FederationUpstream upstream;
   private final AtomicInteger count = new AtomicInteger();
   private final ScheduledExecutorService scheduledExecutorService;
   private final int intialConnectDelayMultiplier = 2;
   private final int intialConnectDelayMax = 30;
   private final ClientSessionCallback clientSessionCallback;
   private boolean started = false;
   private volatile ScheduledFuture currentConnectTask;

   private ClientSessionFactoryInternal clientSessionFactory;
   private ClientSession clientSession;
   private ClientConsumer clientConsumer;

   public FederatedQueueConsumerImpl(Federation federation, ActiveMQServer server, Transformer transformer, FederatedConsumerKey key, FederationUpstream upstream, ClientSessionCallback clientSessionCallback) {
      this.federation = federation;
      this.server = server;
      this.key = key;
      this.transformer = transformer;
      this.upstream = upstream;
      this.scheduledExecutorService = server.getScheduledPool();
      this.clientSessionCallback = clientSessionCallback;
   }

   @Override
   public FederationUpstream getFederationUpstream() {
      return upstream;
   }

   @Override
   public Federation getFederation() {
      return federation;
   }

   @Override
   public FederatedConsumerKey getKey() {
      return key;
   }

   @Override
   public ClientSession getClientSession() {
      return clientSession;
   }

   @Override
   public int incrementCount() {
      return count.incrementAndGet();
   }

   @Override
   public int decrementCount() {
      return count.decrementAndGet();
   }

   @Override
   public synchronized void start() {
      if (!started) {
         started = true;
         scheduleConnect(0);
      }
   }

   private void scheduleConnect(int delay) {
      currentConnectTask = scheduledExecutorService.schedule(() -> {
         try {
            connect();
         } catch (Exception e) {
            int nextDelay = FederatedQueueConsumer.getNextDelay(delay, intialConnectDelayMultiplier, intialConnectDelayMax);
            logger.trace("{} failed to connect. Scheduling reconnect in {} seconds.", this, nextDelay, e);
            scheduleConnect(nextDelay);
         }
      }, delay, TimeUnit.SECONDS);
   }

   private synchronized void connect() throws Exception {
      if (started) {
         try {
            if (clientConsumer == null) {
               this.clientSessionFactory = (ClientSessionFactoryInternal) upstream.getConnection().clientSessionFactory();
               this.clientSession = clientSessionFactory.createSession(upstream.getUser(), upstream.getPassword(), false, true, true, clientSessionFactory.getServerLocator().isPreAcknowledge(), clientSessionFactory.getServerLocator().getAckBatchSize());
               this.clientSession.addFailureListener(this);
               this.clientSession.addMetaData(FEDERATION_NAME, federation.getName().toString());
               this.clientSession.addMetaData(FEDERATION_UPSTREAM_NAME, upstream.getName().toString());
               this.clientSession.start();
               if (clientSessionCallback != null) {
                  clientSessionCallback.callback(clientSession);
               }
               if (clientSession.queueQuery(key.getQueueName()).isExists()) {
                  this.clientConsumer = clientSession.createConsumer(key.getQueueName(), key.getFilterString(), key.getPriority(), false);
                  this.clientConsumer.setMessageHandler(this);
               } else {
                  throw new ActiveMQNonExistentQueueException("Queue " + key.getQueueName() + " does not exist on remote");
               }
            }
         } catch (Exception e) {
            try {
               if (clientSessionFactory != null) {
                  clientSessionFactory.cleanup();
               }
               disconnect();
            } catch (ActiveMQException ignored) {
            }
            throw e;
         }
      }
   }

   @Override
   public synchronized void close() {
      if (started) {
         started = false;
         currentConnectTask.cancel(false);
         scheduleDisconnect(0);
      }
   }

   private void scheduleDisconnect(int delay) {
      scheduledExecutorService.schedule(() -> {
         try {
            disconnect();
         } catch (Exception ignored) {
         }
      }, delay, TimeUnit.SECONDS);
   }

   private void disconnect() throws ActiveMQException {
      if (clientConsumer != null) {
         clientConsumer.close();
         clientConsumer = null;
      }
      if (clientSession != null) {
         clientSession.close();
         clientSession = null;
      }
      if (clientSessionFactory != null && clientSessionFactory.numSessions() == 0 && !upstream.getConnection().isSharedConnection()) {
         clientSessionFactory.close();
         clientSessionFactory = null;
      }
   }

   @Override
   public void onMessage(ClientMessage clientMessage) {
      try {
         Message message = clientMessage;
         if (message instanceof ClientLargeMessageInternal) {

            final StorageManager storageManager = server.getStorageManager();
            LargeServerMessage lsm = storageManager.createLargeMessage(storageManager.generateID(), message);

            LargeData largeData = null;
            do {
               // block on reading all pending chunks, ok as we are called from an executor
               largeData = ((ClientLargeMessageInternal) clientMessage).getLargeMessageController().take();
               lsm.addBytes(largeData.getChunk());
            }
            while (largeData.isContinues());

            message = lsm.toMessage();
            lsm.releaseResources(true, true);
         }

         if (server.hasBrokerFederationPlugins()) {
            try {
               server.callBrokerFederationPlugins(plugin -> plugin.beforeFederatedQueueConsumerMessageHandled(this, clientMessage));
            } catch (ActiveMQException t) {
               ActiveMQServerLogger.LOGGER.federationPluginExecutionError("beforeFederatedQueueConsumerMessageHandled", t);
               throw new IllegalStateException(t.getMessage(), t.getCause());
            }
         }

         message = message.copy(server.getStorageManager().generateID());
         message = transformer == null ? message : transformer.transform(message);
         if (message != null) {
            server.getPostOffice().route(message, true);
         }
         clientMessage.acknowledge();

         if (server.hasBrokerFederationPlugins()) {
            try {
               server.callBrokerFederationPlugins(plugin -> plugin.afterFederatedQueueConsumerMessageHandled(this, clientMessage));
            } catch (ActiveMQException t) {
               ActiveMQServerLogger.LOGGER.federationPluginExecutionError("afterFederatedQueueConsumerMessageHandled", t);
               throw new IllegalStateException(t.getMessage(), t.getCause());
            }
         }
      } catch (Exception e) {
         ActiveMQServerLogger.LOGGER.federationDispatchError(clientMessage.toString(), e);
         try {
            clientSession.rollback();
         } catch (ActiveMQException e1) {
         }
      }
   }

   @Override
   public void connectionFailed(ActiveMQException exception, boolean failedOver) {
      connectionFailed(exception, failedOver, null);
   }

   @Override
   public void connectionFailed(ActiveMQException exception, boolean failedOver, String scaleDownTargetNodeID) {
      try {
         clientSessionFactory.cleanup();
         clientSessionFactory.close();
         clientConsumer = null;
         clientSession = null;
         clientSessionFactory = null;
      } catch (Throwable dontCare) {
      }
      scheduleConnect(0);
   }

   @Override
   public void beforeReconnect(ActiveMQException exception) {
   }

   // used for testing
   public ScheduledFuture getCurrentConnectTask() {
      return currentConnectTask;
   }

   public interface ClientSessionCallback {
      void callback(ClientSession clientSession) throws ActiveMQException;
   }
}
