/*
 * Copyright 2012 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package org.jboss.netty.handler.traffic;

import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelEvent;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.ChannelState;
import org.jboss.netty.channel.ChannelStateEvent;
import org.jboss.netty.channel.MessageEvent;
import org.jboss.netty.channel.SimpleChannelHandler;
import org.jboss.netty.channel.UpstreamChannelStateEvent;
import org.jboss.netty.logging.InternalLogger;
import org.jboss.netty.logging.InternalLoggerFactory;
import org.jboss.netty.util.DefaultObjectSizeEstimator;
import org.jboss.netty.util.ExternalResourceReleasable;
import org.jboss.netty.util.ObjectSizeEstimator;
import org.jboss.netty.util.Timeout;
import org.jboss.netty.util.Timer;
import org.jboss.netty.util.TimerTask;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

/**
 * AbstractTrafficShapingHandler allows to limit the global bandwidth
 * (see {@link GlobalTrafficShapingHandler}) or per session
 * bandwidth (see {@link ChannelTrafficShapingHandler}), as traffic shaping.
 * It allows too to implement an almost real time monitoring of the bandwidth using
 * the monitors from {@link TrafficCounter} that will call back every checkInterval
 * the method doAccounting of this handler.<br>
 * <br>
 *
 * An {@link ObjectSizeEstimator} can be passed at construction to specify what
 * is the size of the object to be read or write accordingly to the type of
 * object. If not specified, it will used the {@link DefaultObjectSizeEstimator} implementation.<br><br>
 *
 * If you want for any particular reasons to stop the monitoring (accounting) or to change
 * the read/write limit or the check interval, several methods allow that for you:<br>
 * <ul>
 * <li><tt>configure</tt> allows you to change read or write limits, or the checkInterval</li>
 * <li><tt>getTrafficCounter</tt> allows you to have access to the TrafficCounter and so to stop
 * or start the monitoring, to change the checkInterval directly, or to have access to its values.</li>
 * <li></li>
 * </ul>
 */
public abstract class AbstractTrafficShapingHandler extends
        SimpleChannelHandler implements ExternalResourceReleasable {
    /**
     * Internal logger
     */
    static InternalLogger logger = InternalLoggerFactory
            .getInstance(AbstractTrafficShapingHandler.class);

    /**
     * Default delay between two checks: 1s
     */
    public static final long DEFAULT_CHECK_INTERVAL = 1000;
    /**
     * Default max delay in case of traffic shaping
     * (during which no communication will occur).
     * Shall be less than TIMEOUT. Here half of "standard" 30s
     */
    public static final long DEFAULT_MAX_TIME = 15000;

    /**
     * Default max size to not exceed in buffer (write only).
     */
    public static final long DEFAULT_MAX_SIZE = 4 * 1024 * 1024L;

    /**
     * Default minimal time to wait
     */
    static final long MINIMAL_WAIT = 10;

    /**
     * Traffic Counter
     */
    protected TrafficCounter trafficCounter;

    /**
     * ObjectSizeEstimator
     */
    private ObjectSizeEstimator objectSizeEstimator;

    /**
     * Timer associated to any TrafficCounter
     */
    protected Timer timer;

    /**
     * used in releaseExternalResources() to cancel the timer
     */
    private volatile Timeout timeout;

    /**
     * Limit in B/s to apply to write
     */
    private long writeLimit;

    /**
     * Limit in B/s to apply to read
     */
    private long readLimit;

    /**
     * Delay between two performance snapshots
     */
    protected long checkInterval = DEFAULT_CHECK_INTERVAL; // default 1 s
    /**
     * Max delay in wait
     */
    protected long maxTime = DEFAULT_MAX_TIME; // default 15 s

    /**
     * Max time to delay before proposing to stop writing new objects from next handlers
     */
    protected long maxWriteDelay = 4 * DEFAULT_CHECK_INTERVAL; // default 4 s
    /**
     * Max size in the list before proposing to stop writing new objects from next handlers
     */
    protected long maxWriteSize = DEFAULT_MAX_SIZE; // default 4MB
    /**
     * Boolean associated with the release of this TrafficShapingHandler.
     * It will be true only once when the releaseExternalRessources is called
     * to prevent waiting when shutdown.
     */
    final AtomicBoolean release = new AtomicBoolean(false);

    /**
     * Attachment of ChannelHandlerContext
     *
     */
    protected static class ReadWriteStatus {
        volatile boolean readSuspend;
        volatile boolean writeSuspend;
        volatile boolean writeSuspendFromChannel;
        ReentrantLock lock = new ReentrantLock(true);
    }

     private void init(ObjectSizeEstimator newObjectSizeEstimator,
             Timer newTimer, long newWriteLimit, long newReadLimit,
             long newCheckInterval, long newMaxTime) {
         objectSizeEstimator = newObjectSizeEstimator;
         timer = newTimer;
         writeLimit = newWriteLimit;
         readLimit = newReadLimit;
         checkInterval = newCheckInterval;
         maxTime = newMaxTime;
         //logger.warn("TSH: "+writeLimit+":"+readLimit+":"+checkInterval);
     }

    /**
     *
     * @param newTrafficCounter the TrafficCounter to set
     */
    void setTrafficCounter(TrafficCounter newTrafficCounter) {
        trafficCounter = newTrafficCounter;
    }

    /**
     * Constructor using default {@link ObjectSizeEstimator}
     *
     * @param timer
     *          created once for instance like HashedWheelTimer(10, TimeUnit.MILLISECONDS, 1024)
     * @param writeLimit
     *          0 or a limit in bytes/s
     * @param readLimit
     *          0 or a limit in bytes/s
     * @param checkInterval
     *          The delay between two computations of performances for
     *            channels or 0 if no stats are to be computed
     */
    protected AbstractTrafficShapingHandler(Timer timer, long writeLimit,
                                            long readLimit, long checkInterval) {
        init(new DefaultObjectSizeEstimator(), timer, writeLimit, readLimit, checkInterval,
                DEFAULT_MAX_TIME);
    }

    /**
     * Constructor using the specified ObjectSizeEstimator
     *
     * @param objectSizeEstimator
     *            the {@link ObjectSizeEstimator} that will be used to compute
     *            the size of the message
     * @param timer
     *          created once for instance like HashedWheelTimer(10, TimeUnit.MILLISECONDS, 1024)
     * @param writeLimit
     *          0 or a limit in bytes/s
     * @param readLimit
     *          0 or a limit in bytes/s
     * @param checkInterval
     *          The delay between two computations of performances for
     *            channels or 0 if no stats are to be computed
     */
    protected AbstractTrafficShapingHandler(
            ObjectSizeEstimator objectSizeEstimator, Timer timer,
            long writeLimit, long readLimit, long checkInterval) {
        init(objectSizeEstimator, timer, writeLimit, readLimit, checkInterval, DEFAULT_MAX_TIME);
    }

    /**
     * Constructor using default {@link ObjectSizeEstimator} and using default Check Interval
     *
     * @param timer
     *          created once for instance like HashedWheelTimer(10, TimeUnit.MILLISECONDS, 1024)
     * @param writeLimit
     *          0 or a limit in bytes/s
     * @param readLimit
     *          0 or a limit in bytes/s
     */
    protected AbstractTrafficShapingHandler(Timer timer, long writeLimit,
                                            long readLimit) {
        init(new DefaultObjectSizeEstimator(), timer, writeLimit, readLimit,
                DEFAULT_CHECK_INTERVAL, DEFAULT_MAX_TIME);
    }

    /**
     * Constructor using the specified ObjectSizeEstimator and using default Check Interval
     *
     * @param objectSizeEstimator
     *            the {@link ObjectSizeEstimator} that will be used to compute
     *            the size of the message
     * @param timer
     *          created once for instance like HashedWheelTimer(10, TimeUnit.MILLISECONDS, 1024)
     * @param writeLimit
     *          0 or a limit in bytes/s
     * @param readLimit
     *          0 or a limit in bytes/s
     */
    protected AbstractTrafficShapingHandler(
            ObjectSizeEstimator objectSizeEstimator, Timer timer,
            long writeLimit, long readLimit) {
        init(objectSizeEstimator, timer, writeLimit, readLimit,
                DEFAULT_CHECK_INTERVAL, DEFAULT_MAX_TIME);
    }

    /**
     * Constructor using default {@link ObjectSizeEstimator} and using NO LIMIT and default Check Interval
     *
     * @param timer
     *          created once for instance like HashedWheelTimer(10, TimeUnit.MILLISECONDS, 1024)
     */
    protected AbstractTrafficShapingHandler(Timer timer) {
        init(new DefaultObjectSizeEstimator(), timer, 0, 0,
                DEFAULT_CHECK_INTERVAL, DEFAULT_MAX_TIME);
    }

    /**
     * Constructor using the specified ObjectSizeEstimator and using NO LIMIT and default Check Interval
     *
     * @param objectSizeEstimator
     *            the {@link ObjectSizeEstimator} that will be used to compute
     *            the size of the message
     * @param timer
     *          created once for instance like HashedWheelTimer(10, TimeUnit.MILLISECONDS, 1024)
     */
    protected AbstractTrafficShapingHandler(
            ObjectSizeEstimator objectSizeEstimator, Timer timer) {
        init(objectSizeEstimator, timer, 0, 0,
                DEFAULT_CHECK_INTERVAL, DEFAULT_MAX_TIME);
    }

    /**
     * Constructor using default {@link ObjectSizeEstimator} and using NO LIMIT
     *
     * @param timer
     *          created once for instance like HashedWheelTimer(10, TimeUnit.MILLISECONDS, 1024)
     * @param checkInterval
     *          The delay between two computations of performances for
     *            channels or 0 if no stats are to be computed
     */
    protected AbstractTrafficShapingHandler(Timer timer, long checkInterval) {
        init(new DefaultObjectSizeEstimator(), timer, 0, 0, checkInterval, DEFAULT_MAX_TIME);
    }

    /**
     * Constructor using the specified ObjectSizeEstimator and using NO LIMIT
     *
     * @param objectSizeEstimator
     *            the {@link ObjectSizeEstimator} that will be used to compute
     *            the size of the message
     * @param timer
     *          created once for instance like HashedWheelTimer(10, TimeUnit.MILLISECONDS, 1024)
     * @param checkInterval
     *          The delay between two computations of performances for
     *            channels or 0 if no stats are to be computed
     */
    protected AbstractTrafficShapingHandler(
            ObjectSizeEstimator objectSizeEstimator, Timer timer,
            long checkInterval) {
        init(objectSizeEstimator, timer, 0, 0, checkInterval, DEFAULT_MAX_TIME);
    }

    /**
     * Constructor using default {@link ObjectSizeEstimator}
     *
     * @param timer
     *          created once for instance like HashedWheelTimer(10, TimeUnit.MILLISECONDS, 1024)
     * @param writeLimit
     *          0 or a limit in bytes/s
     * @param readLimit
     *          0 or a limit in bytes/s
     * @param checkInterval
     *          The delay between two computations of performances for
     *            channels or 0 if no stats are to be computed
     * @param maxTime
     *          The max time to wait in case of excess of traffic (to prevent Time Out event)
     */
    protected AbstractTrafficShapingHandler(Timer timer, long writeLimit,
                                            long readLimit, long checkInterval, long maxTime) {
        init(new DefaultObjectSizeEstimator(), timer, writeLimit, readLimit, checkInterval,
                maxTime);
    }

    /**
     * Constructor using the specified ObjectSizeEstimator
     *
     * @param objectSizeEstimator
     *            the {@link ObjectSizeEstimator} that will be used to compute
     *            the size of the message
     * @param timer
     *          created once for instance like HashedWheelTimer(10, TimeUnit.MILLISECONDS, 1024)
     * @param writeLimit
     *          0 or a limit in bytes/s
     * @param readLimit
     *          0 or a limit in bytes/s
     * @param checkInterval
     *          The delay between two computations of performances for
     *            channels or 0 if no stats are to be computed
     * @param maxTime
     *          The max time to wait in case of excess of traffic (to prevent Time Out event)
     */
    protected AbstractTrafficShapingHandler(
            ObjectSizeEstimator objectSizeEstimator, Timer timer,
            long writeLimit, long readLimit, long checkInterval, long maxTime) {
        init(objectSizeEstimator, timer, writeLimit, readLimit, checkInterval, maxTime);
    }

    /**
     * Change the underlying limitations and check interval.
     */
    public void configure(long newWriteLimit, long newReadLimit,
            long newCheckInterval) {
        configure(newWriteLimit, newReadLimit);
        configure(newCheckInterval);
    }

    /**
     * Change the underlying limitations.
     */
    public void configure(long newWriteLimit, long newReadLimit) {
        writeLimit = newWriteLimit;
        readLimit = newReadLimit;
        if (trafficCounter != null) {
            trafficCounter.resetAccounting(System.currentTimeMillis() + 1);
        }
    }

    /**
     * Change the check interval.
     */
    public void configure(long newCheckInterval) {
        setCheckInterval(newCheckInterval);
    }

    /**
     * @return the writeLimit
     */
    public long getWriteLimit() {
        return writeLimit;
    }

    /**
     * @param writeLimit the writeLimit to set
     */
    public void setWriteLimit(long writeLimit) {
        this.writeLimit = writeLimit;
        if (trafficCounter != null) {
            trafficCounter.resetAccounting(System.currentTimeMillis() + 1);
        }
    }

    /**
     * @return the readLimit
     */
    public long getReadLimit() {
        return readLimit;
    }

    /**
     * @param readLimit the readLimit to set
     */
    public void setReadLimit(long readLimit) {
        this.readLimit = readLimit;
        if (trafficCounter != null) {
            trafficCounter.resetAccounting(System.currentTimeMillis() + 1);
        }
    }

    /**
     * @return the checkInterval
     */
    public long getCheckInterval() {
        return checkInterval;
    }

    /**
     * @param newCheckInterval the checkInterval to set
     */
    public void setCheckInterval(long newCheckInterval) {
        this.checkInterval = newCheckInterval;
        if (trafficCounter != null) {
            trafficCounter.configure(checkInterval);
        }
    }

    /**
     * @return the max delay on wait
     */
    public long getMaxTimeWait() {
        return maxTime;
    }

    /**
    *
    * @param maxTime
    *    Max delay in wait, shall be less than TIME OUT in related protocol
    */
   public void setMaxTimeWait(long maxTime) {
       this.maxTime = maxTime;
   }

   /**
    * @return the maxWriteDelay
    */
   public long getMaxWriteDelay() {
       return maxWriteDelay;
   }

   /**
    * @param maxWriteDelay the maximum Write Delay in ms in the buffer allowed before write suspended is set
    */
   public void setMaxWriteDelay(long maxWriteDelay) {
       this.maxWriteDelay = maxWriteDelay;
   }

   /**
    * @return the maxWriteSize
    */
   public long getMaxWriteSize() {
       return maxWriteSize;
   }

   /**
    * @param maxWriteSize the maximum Write Size allowed in the buffer
    *            per channel before write suspended is set
    */
   public void setMaxWriteSize(long maxWriteSize) {
       this.maxWriteSize = maxWriteSize;
   }

    /**
     * Called each time the accounting is computed from the TrafficCounters.
     * This method could be used for instance to implement almost real time accounting.
     *
     * @param counter
     *            the TrafficCounter that computes its performance
     */
    protected void doAccounting(TrafficCounter counter) {
        // NOOP by default
    }

    /**
     * Class to implement setReadable at fix time
     */
    private class ReopenReadTimerTask implements TimerTask {
        final ChannelHandlerContext ctx;
        ReopenReadTimerTask(ChannelHandlerContext ctx) {
            this.ctx = ctx;
        }
        public void run(Timeout timeoutArg) throws Exception {
            //logger.warn("Start RRTT: "+release.get());
            if (release.get()) {
                return;
            }
            ReadWriteStatus rws = checkAttachment(ctx);
            if (!ctx.getChannel().isReadable() && ! rws.readSuspend) {
                // If isReadable is False and Active is True, user make a direct setReadable(false)
                // Then Just reset the status
                if (logger.isDebugEnabled()) {
                    logger.debug("Not Unsuspend: " + ctx.getChannel().isReadable() + ":" +
                            rws.readSuspend);
                }
                rws.readSuspend = false;
            } else {
                // Anything else allows the handler to reset the AutoRead
                if (logger.isDebugEnabled()) {
                    if (ctx.getChannel().isReadable() && rws.readSuspend) {
                        logger.debug("Unsuspend: " + ctx.getChannel().isReadable() + ":" +
                                rws.readSuspend);
                    } else {
                        logger.debug("Normal Unsuspend: " + ctx.getChannel().isReadable() + ":" +
                                rws.readSuspend);
                    }
                }
                rws.readSuspend = false;
                ctx.getChannel().setReadable(true);
            }
            if (logger.isDebugEnabled()) {
                logger.debug("Unsupsend final status => " + ctx.getChannel().isReadable() + ":" +
                        rws.readSuspend);
            }
        }
    }

    @Override
    public void messageReceived(ChannelHandlerContext ctx, MessageEvent evt)
            throws Exception {
        try {
            ReadWriteStatus rws = checkAttachment(ctx);
            long size = calculateSize(evt.getMessage());
            if (size > 0 && trafficCounter != null) {
                // compute the number of ms to wait before reopening the channel
                long wait = trafficCounter.readTimeToWait(size, readLimit, maxTime);
                wait = checkWaitReadTime(ctx, wait);
                if (wait >= MINIMAL_WAIT) { // At least 10ms seems a minimal
                    // time in order to try to limit the traffic
                    if (release.get()) {
                        return;
                    }
                    Channel channel = ctx.getChannel();
                    if (channel != null && channel.isConnected()) {
                        // Only AutoRead AND HandlerActive True means Context Active
                        if (logger.isDebugEnabled()) {
                            logger.debug("Read Suspend: " + wait + ":" + channel.isReadable() + ":" +
                                    rws.readSuspend);
                        }
                        if (timer == null) {
                            // Sleep since no executor
                            // logger.warn("Read sleep since no timer for "+wait+" ms for "+this);
                            Thread.sleep(wait);
                            return;
                        }
                        if (channel.isReadable() && ! rws.readSuspend) {
                            rws.readSuspend = true;
                            channel.setReadable(false);
                            if (logger.isDebugEnabled()) {
                                logger.debug("Suspend final status => " + channel.isReadable() + ":" +
                                        rws.readSuspend);
                            }
                            // Create a Runnable to reactive the read if needed. If one was create before
                            // it will just be reused to limit object creation
                            TimerTask timerTask = new ReopenReadTimerTask(ctx);
                            timeout = timer.newTimeout(timerTask, wait,
                                    TimeUnit.MILLISECONDS);
                        }
                    }
                }
            }
        } finally {
            informReadOperation(ctx);
            // The message is then just passed to the next handler
            super.messageReceived(ctx, evt);
        }
    }

    /**
     * Method overridden in GTSH to take into account specific timer for the channel
     * @param ctx
     * @param wait
     * @return the wait to use according to the context
     */
    protected long checkWaitReadTime(final ChannelHandlerContext ctx, long wait) {
        // no change by default
        return wait;
    }

    /**
     * Method overridden in GTSH to take into account specific timer for the channel
     * @param ctx
     */
    protected void informReadOperation(final ChannelHandlerContext ctx) {
        // default noop
    }

    @Override
    public void writeRequested(ChannelHandlerContext ctx, MessageEvent evt)
            throws Exception {
        long wait = 0;
        ReadWriteStatus rws = checkAttachment(ctx);
        long size = calculateSize(evt.getMessage());
        try {
            if (size > 0 && trafficCounter != null) {
                // compute the number of ms to wait before continue with the channel
                wait = trafficCounter.writeTimeToWait(size, writeLimit, maxTime);
                if (logger.isDebugEnabled()) {
                    logger.debug("Write Suspend: " + wait + ":" + ctx.getChannel().isWritable() + ":" +
                            rws.writeSuspend);
                }
                if (wait >= MINIMAL_WAIT) {
                    if (release.get()) {
                        return;
                    }
                } else {
                    wait = 0;
                }
            }
        } finally {
            if (release.get()) {
                return;
            }
            // The message is scheduled
            submitWrite(ctx, evt, size, wait);
        }
    }

    protected void internalSubmitWrite(ChannelHandlerContext ctx, MessageEvent evt) throws Exception {
        super.writeRequested(ctx, evt);
    }

    protected abstract void submitWrite(final ChannelHandlerContext ctx, final MessageEvent evt, final long size,
            final long delay) throws Exception;

    protected void setWritable(ChannelHandlerContext ctx, boolean writable) {
        ReadWriteStatus rws = checkAttachment(ctx);
        if (rws.writeSuspend == !writable && ! writable) {
            return;
        }
        rws.writeSuspend = ! writable;
        Channel channel = ctx.getChannel();
        if (! writable) {
            ctx.sendUpstream(
                    new UpstreamChannelStateEvent(
                            channel, ChannelState.INTEREST_OPS, Channel.OP_WRITE));
        } else {
            // reset the suspend from Channel if any
            rws.writeSuspendFromChannel = false;
            ctx.sendUpstream(
                    new UpstreamChannelStateEvent(
                            channel, ChannelState.INTEREST_OPS, Channel.OP_NONE));
        }
    }

    @Override
    public void channelInterestChanged(ChannelHandlerContext ctx, ChannelStateEvent e)
            throws Exception {
        ReadWriteStatus rws = checkAttachment(ctx);
        if (e.getState() == ChannelState.INTEREST_OPS) {
            if ((((Integer) e.getValue()).intValue() & Channel.OP_WRITE) == 0
                && rws.writeSuspend && ! rws.writeSuspendFromChannel) {
                // silently ignored since already in Write Suspension from this handler
                return;
            } else if ((((Integer) e.getValue()).intValue() & Channel.OP_WRITE) != 0) {
                // force Suspension due to Channel limit
                rws.writeSuspendFromChannel = true;
                setWritable(ctx, false);
                return;
            }
            if (rws.writeSuspend) {
                return;
            }
            // Coming here if not OP_WRITE and not handler suspended and system suspended
            rws.writeSuspendFromChannel = false;
        }
        super.channelInterestChanged(ctx, e);
    }

    /**
     * Check the writability according to delay and size for the channel.
     * Set if necessary WRITE_SUSPENDED status.
     * @param ctx
     * @param delay
     * @param queueSize
     */
    protected void checkWriteSuspend(ChannelHandlerContext ctx, long delay, long queueSize) {
        if (queueSize > maxWriteSize || delay > maxWriteDelay) {
            setWritable(ctx, false);
        }
    }
    /**
     * Explicitly release the Write suspended status and trigger the event WRITE_ENABLED
     * @param ctx
     */
    protected void releaseWriteSuspended(ChannelHandlerContext ctx) {
        setWritable(ctx, true);
    }
    /**
     * Check if the current channel is WRITE SUSPENDED by a TrafficShapingHandler
     * @param ctx
     * @return True if write is in Suspended status
     */
    public static boolean checkWriteSuspended(ChannelHandlerContext ctx) {
        ReadWriteStatus rws = checkAttachment(ctx);
        return ! rws.writeSuspend;
    }

    @Override
    public void handleDownstream(ChannelHandlerContext ctx, ChannelEvent e)
            throws Exception {
        if (e instanceof ChannelStateEvent) {
            ChannelStateEvent cse = (ChannelStateEvent) e;
            if (cse.getState() == ChannelState.INTEREST_OPS &&
                    (((Integer) cse.getValue()).intValue() & Channel.OP_READ) != 0) {

                // setReadable(true) requested
                ReadWriteStatus rws = checkAttachment(ctx);
                if (rws.readSuspend) {
                    // Drop the request silently if this handler has
                    // set the flag.
                    e.getFuture().setSuccess();
                    return;
                }
            }
        }
        super.handleDownstream(ctx, e);
    }

    /**
     *
     * @return the current TrafficCounter (if
     *         channel is still connected)
     */
    public TrafficCounter getTrafficCounter() {
        return trafficCounter;
    }

    public void releaseExternalResources() {
        if (trafficCounter != null) {
            trafficCounter.stop();
        }
        release.set(true);
        if (timeout != null) {
            timeout.cancel();
        }
        //shall be done outside (since it can be shared): timer.stop();
    }

    protected static synchronized ReadWriteStatus checkAttachment(ChannelHandlerContext ctx) {
        ReadWriteStatus rws = (ReadWriteStatus) ctx.getAttachment();
        if (rws == null) {
            rws = new ReadWriteStatus();
            ctx.setAttachment(rws);
        }
        return rws;
    }
    @Override
    public void channelConnected(ChannelHandlerContext ctx, ChannelStateEvent e) throws Exception {
        checkAttachment(ctx);
        super.channelConnected(ctx, e);
    }

    protected long calculateSize(Object obj) {
        long size = objectSizeEstimator.estimateSize(obj);
        //logger.debug("Size: "+size);
        return size;
    }
    @Override
    public String toString() {
        return "TrafficShaping with Write Limit: " + writeLimit +
                " Read Limit: " + readLimit + " every: " + checkInterval + " maxDelay: " + maxWriteDelay +
                " maxSize: " + maxWriteSize + " and Counter: " +
                (trafficCounter != null? trafficCounter.toString() : "none");
    }
}
