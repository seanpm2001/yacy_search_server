// plasmaDHTTransfer.java 
// ------------------------------
// part of YaCy
// (C) by Michael Peter Christen; mc@yacy.net
// first published on http://www.anomic.de
// Frankfurt, Germany, 2005, 2006
// 
// This class was provided by Martin Thelian
//
// This program is free software; you can redistribute it and/or modify
// it under the terms of the GNU General Public License as published by
// the Free Software Foundation; either version 2 of the License, or
// (at your option) any later version.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
// GNU General Public License for more details.
//
// You should have received a copy of the GNU General Public License
// along with this program; if not, write to the Free Software
// Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA

package de.anomic.plasma;

import java.util.HashMap;

import de.anomic.server.logging.serverLog;
import de.anomic.yacy.yacyClient;
import de.anomic.yacy.yacyPeerActions;
import de.anomic.yacy.yacySeed;
import de.anomic.yacy.yacySeedDB;

public class plasmaDHTTransfer extends Thread {

    public static final int TRANSFER_MODE_DISTRIBUTION = 0;
    public static final int TRANSFER_MODE_FLUSH = 1;
    
    // connection properties
    private boolean gzipBody4Transfer = false;
    private int timeout4Transfer;    

    // status fields
    private boolean stopped = false;
    private long transferTime = 0;
    private long payloadSize = 0;
    private int transferStatus = plasmaDHTChunk.chunkStatus_UNDEFINED;
    private String transferStatusMessage = "";

    // delivery destination
    private yacySeed seed = null;

    // word chunk
    plasmaDHTChunk dhtChunk;

    // other fields
    private final yacySeedDB seedDB;
    private final yacyPeerActions peerActions;
    private final int maxRetry;
    private int transferMode = TRANSFER_MODE_DISTRIBUTION;
    serverLog log;

    public plasmaDHTTransfer(
            final serverLog log,
            final yacySeedDB seedDB,
            final yacyPeerActions peerActions,
            final yacySeed destSeed, 
            final plasmaDHTChunk dhtChunk, 
            final boolean gzipBody, 
            final int timeout, 
            final int retries
    ) {
        super(new ThreadGroup("TransferIndexThreadGroup"), "TransferIndexWorker_" + destSeed.getName());
        this.log = log;
        this.seedDB = seedDB;
        this.peerActions = peerActions;
        this.gzipBody4Transfer = gzipBody;
        this.timeout4Transfer = timeout;
        this.dhtChunk = dhtChunk;
        this.maxRetry = retries;
        this.seed = destSeed;
    }

    public void setTransferMode(final int mode) {
        this.transferMode = mode;
    }
    
    public void run() {
        try {
            this.uploadIndex();
        } catch (final InterruptedException e) {
            e.printStackTrace();
        }
    }
    
    private boolean isAborted() {
        if (this.stopped || Thread.currentThread().isInterrupted()) {
            this.transferStatus = plasmaDHTChunk.chunkStatus_INTERRUPTED;
            this.transferStatusMessage = "aborted"; 
            return true;
        }
        return false;
    }

    public void stopIt() {
        this.stopped = true;
    }

    public long getTransferTime() {
        return this.transferTime;
    }
    
    public long getPayloadSize() {
        return this.payloadSize;
    }
    
    public int getStatus() {
        return this.transferStatus;
    }

    public String getStatusMessage() {
        return this.transferStatusMessage;
    }
    
    public yacySeed getSeed() {
        return this.seed;
    }
    
    public void uploadIndex() throws InterruptedException {

        /* loop until we 
         * - have successfully transfered the words list or 
         * - the retry counter limit was exceeded
         */
        this.transferStatus = plasmaDHTChunk.chunkStatus_RUNNING;
        long retryCount = 0;
        final long start = System.currentTimeMillis();
        while (true) {
            // testing if we were aborted
            if (this.isAborted()) return;

            // transfering seleted words to remote peer
            this.transferStatusMessage = "Running: Transfering chunk to target " + this.seed.hash + "/" + this.seed.getName();
            final HashMap<String, Object> result = yacyClient.transferIndex(this.seedDB, this.seed, this.dhtChunk.containers(), this.dhtChunk.urlCacheMap(), this.gzipBody4Transfer, this.timeout4Transfer);
            final String error = (String) result.get("result");
            if (error == null) {
                // words successfully transfered
                this.transferTime = System.currentTimeMillis() - start;                
                this.payloadSize = ((Integer)result.get("payloadSize")).intValue();
                
                this.log.logInfo("Index transfer of " + this.dhtChunk.indexCount() + 
                                 " entries " + this.dhtChunk.containerSize() +
                                 " words [" + this.dhtChunk.firstContainer().getWordHash() + " .. " + this.dhtChunk.lastContainer().getWordHash() + "]" + 
                                 " and " + this.dhtChunk.urlCacheMap().size() + " URLs" +
                                 " to peer " + this.seed.getName() + ":" + this.seed.hash + 
                                 " in " + (this.transferTime / 1000) + 
                                 " seconds successful ("  + (1000 * this.dhtChunk.indexCount() / (this.transferTime + 1)) + 
                                 " words/s, " + this.payloadSize + " Bytes)");
                
                // if the peer has set a pause time and we are in flush mode (index transfer)
                // then we pause for a while now
                if (this.transferMode == TRANSFER_MODE_FLUSH) {
                    final long pause = getBusyTime(result);
                    if (pause != -1) {
                        this.transferStatusMessage = "Finished: Transfer of chunk to target " + this.seed.hash + "/" + this.seed.getName() + ". Pausing " + pause + " ms.";
                        this.pause(pause);                        
                    }
                } else {
                    this.transferStatusMessage = "Finished: Transfer of chunk to target " + this.seed.hash + "/" + this.seed.getName();
                }
                
                // transfer of chunk finished
                this.transferStatus = plasmaDHTChunk.chunkStatus_COMPLETE;
                retryCount = 0;
                
                break;
            } 
            
            // inc retry counter
            retryCount++;
            if (this.isAborted()) return;                      

            boolean reconnectNeeded = false;
            long pauseTime = 1;
            
            if (error.equals("busy")) {
                // get pause time that was requested by the remote peer
                pauseTime = getBusyTime(result);
                if (pauseTime == -1) pauseTime = 60000;
                
                this.transferStatusMessage = "Peer " + this.seed.getName() + ":" + this.seed.hash + " is busy. Waiting " + pauseTime + " ms.";
                this.log.logInfo(this.transferStatusMessage);              
            } else {
                this.transferStatusMessage = "Transfer to peer " + this.seed.getName() + ":" + this.seed.hash + " failed:'" + error + "', Trying to reconnect ...";
                
                // force disconnection of peer
                peerActions.peerDeparture(this.seed, "DHT Transfer: " + this.transferStatusMessage);
                this.log.logWarning(this.transferStatusMessage);
                
                // calculate pause time
                pauseTime = retryCount * 10000;
                reconnectNeeded = true;
            }
            
            // if the retry counter limit was not exceeded we'll retry it in a few seconds            
            if (retryCount > this.maxRetry) {
                this.transferStatusMessage = "Transfer aborted. Retry limit reached.";
                this.transferStatus = plasmaDHTChunk.chunkStatus_FAILED;
                return;
            }  

            // sleep for a while
            this.pause(pauseTime);  
            
            // reconnect to peer if needed
            if (reconnectNeeded) {
                
                /* loop until 
                 * - we have successfully done a peer ping or 
                 * - the retry counter limit was exceeded
                 */
                while (true) {
                    // testing if we were aborted ...
                    if (this.isAborted())
                        return;

                    // doing a peer ping to the remote seed
                    final int added = yacyClient.publishMySeed(this.seedDB.mySeed(), this.peerActions, this.seed.getPublicAddress(), this.seed.hash);
                    if (added < 0) {
                        // inc. retry counter
                        retryCount++;
                        this.transferStatusMessage = "Disconnected peer: Peer ping failed. " + ((retryCount > 5) ? "Transfer aborted." : "Retry " + retryCount);
                        if (retryCount > this.maxRetry) return;
                        this.pause(retryCount * 10000);
                        continue;
                    }

                    this.seedDB.getConnected(this.seed.hash);
                    this.transferStatusMessage = "running";
                    break;
                }
            }
        }
    }
    
    @SuppressWarnings("unchecked")
    private long getBusyTime(final HashMap<String, Object> result) {
        int pause = -1;
        final Object transferRWIResult = result.get("resultTransferRWI");
        assert transferRWIResult instanceof HashMap;
        if (transferRWIResult != null && ((HashMap<String, String>) transferRWIResult).containsKey("pause")) {
            final String pauseStr = ((HashMap<String, String>) transferRWIResult).get("pause");
            try { pause = Integer.valueOf(pauseStr).intValue(); } catch (final NumberFormatException numEx){}
            if (pause < 0) pause = 5000;
            else if (pause > 30000) pause = 30000;
        }        
        return pause;     
    }
    
    private void pause(final long sleepTime) throws InterruptedException {        
        if (sleepTime == 0) return;
        long sleepCounter = sleepTime / 1000;
        final long sleepRest = sleepTime % 1000;
        while (!this.isAborted() && sleepCounter > 0) {
            sleepCounter--;
            Thread.sleep(1000);            
        }     
        if (sleepRest > 0) Thread.sleep(sleepRest);
    }
}
