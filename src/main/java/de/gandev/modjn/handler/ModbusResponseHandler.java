package de.gandev.modjn.handler;

import de.gandev.modjn.ModbusConstants;
import de.gandev.modjn.entity.ModbusFrame;
import de.gandev.modjn.entity.exception.ErrorResponseException;
import de.gandev.modjn.entity.exception.NoResponseException;
import de.gandev.modjn.entity.func.ModbusError;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 *
 * @author ares
 */
public abstract class ModbusResponseHandler extends SimpleChannelInboundHandler<ModbusFrame> {

    private static final Logger logger = Logger.getLogger(ModbusResponseHandler.class.getSimpleName());
    private final Map<Integer, ModbusFrame> responses = new HashMap<>(ModbusConstants.TRANSACTION_IDENTIFIER_MAX);
    private final Map<Integer, Long> requestStartedAt = new HashMap<>(ModbusConstants.TRANSACTION_IDENTIFIER_MAX);
    private final Map<Integer, Boolean> timedout = new HashMap<>(ModbusConstants.TRANSACTION_IDENTIFIER_MAX);

    private static long statsTotalRequests = 0;
    private static long statsAccumulatedTime = 0;
    private static long statsFastestRequest = Long.MAX_VALUE;
    private static long statsLostRequests = 0;
    private static List<Long> statsTopTen = new ArrayList<Long>();

    public ModbusFrame getResponse(int transactionIdentifier)
            throws NoResponseException, ErrorResponseException {

        long timeoutTime = System.currentTimeMillis() + ModbusConstants.SYNC_RESPONSE_TIMEOUT;
        requestStartedAt.put(transactionIdentifier, System.currentTimeMillis());
        ModbusFrame frame;
        do {
            frame = responses.get(transactionIdentifier);
            if (frame == null) {
                try {
                    Thread.sleep( 10 );
                } catch( InterruptedException e ) {
                    // not important here, so just ignore it
                }
            }
        } while (frame == null && (timeoutTime - System.currentTimeMillis()) > 0);

        if (frame != null) {
            responses.remove(transactionIdentifier);
        }

        if (frame == null) {
            logger.severe( "Missing response to request with transaction id " + transactionIdentifier + ". The Modbus server didn't send a response within " +  ModbusConstants.SYNC_RESPONSE_TIMEOUT + "ms. Throwing a NoResponseException now.");
            timedout.put(transactionIdentifier, true);
            throw new NoResponseException();
        } else if (frame.getFunction() instanceof ModbusError) {
            throw new ErrorResponseException((ModbusError) frame.getFunction());
        }

        return frame;
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        logger.log(Level.SEVERE, cause.getLocalizedMessage());
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, ModbusFrame response) throws Exception {
        boolean doLog = ModbusConstants.PERFORMANCE_LOGGING_ACTIVE;
        int txid = response.getHeader().getTransactionIdentifier();
        if (!timedout.containsKey(txid)) {
            responses.put( response.getHeader().getTransactionIdentifier(), response );
            newResponse(response);
        }
        long duration = System.currentTimeMillis() - requestStartedAt.get(txid);
        statistics(duration);
        requestStartedAt.remove( txid );
        String msg = "Storing the response of the PLC in the modjn hashmap. Transaction id:" + txid + " Duration: " + duration + " ms.";
        if (timedout.containsKey(txid)) {
            doLog = true;
            msg += " The request took too long. In the meantime, the timeout has triggered.";
            timedout.remove( txid );
            synchronized( statsTopTen ) {
                statsLostRequests++;
            }
        }
        if (responses.size() > 1) {
            msg+=" The hashmap now contains " + responses.size() + " entries.";
        }
        if (doLog) {
            logger.info( msg );
        }

    }

    public abstract void newResponse(ModbusFrame frame);

    private void statistics(long time) {
        if (ModbusConstants.PERFORMANCE_STATISTICS_ACTIVE) {
            synchronized( statsTopTen ) {
                statsAccumulatedTime += time;
                if( time < statsFastestRequest ) {
                    statsFastestRequest = time;
                }
                if( statsTopTen.size() < 10 ) {
                    statsTopTen.add( time );
                    Collections.sort( statsTopTen );
                } else {
                    if( time > statsTopTen.get( 0 ) ) {
                        statsTopTen.set( 0, time );
                        Collections.sort( statsTopTen );
                    }
                }
                statsTotalRequests++;
            }
        }
    }

    public static void logStatistics() {
        synchronized( statsTopTen ) {
            logger.info( "Summary of the last " + statsTotalRequests + " Modbus requests:" );
            logger.info( "Average duration: " + statsAccumulatedTime / statsTotalRequests + " ms" );
            logger.info( "Lost requests: " + statsLostRequests );

            logger.info( "Fastest request: " + statsFastestRequest + " ms" );
            logger.info( "Slowest request: " + statsTopTen.get( statsTopTen.size() - 1 ) + " ms" );

            logger.info( statsTopTen.size() + " requests took more than " + statsTopTen.get( 0 ) + " ms" );

            statsTotalRequests = 0;
            statsAccumulatedTime = 0;
            statsFastestRequest = Long.MAX_VALUE;
            statsLostRequests = 0;
            statsTopTen.clear();
        }
}
}
