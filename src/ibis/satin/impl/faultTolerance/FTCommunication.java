/*
 * Created on Apr 26, 2006 by rob
 */

package ibis.satin.impl.faultTolerance;

import ibis.ipl.IbisIdentifier;
import ibis.ipl.ReadMessage;
import ibis.ipl.ReceivePort;
import ibis.ipl.ReceivePortConnectUpcall;
import ibis.ipl.ReceivePortIdentifier;
import ibis.ipl.Registry;
import ibis.ipl.RegistryEventHandler;
import ibis.ipl.SendPort;
import ibis.ipl.SendPortDisconnectUpcall;
import ibis.ipl.SendPortIdentifier;
import ibis.ipl.WriteMessage;
import ibis.satin.impl.Config;
import ibis.satin.impl.Satin;
import ibis.satin.impl.communication.Protocol;
import ibis.satin.impl.loadBalancing.Victim;
import ibis.satin.impl.spawnSync.InvocationRecord;
import ibis.satin.impl.spawnSync.ReturnRecord;
import ibis.satin.impl.spawnSync.Stamp;
import ibis.util.Timer;

import java.io.IOException;
import java.util.Map;

final class FTCommunication implements Config, ReceivePortConnectUpcall,
        SendPortDisconnectUpcall, RegistryEventHandler {
    private Satin s;

    private JoinThread joinThread;

    private boolean connectionUpcallsDisabled = false;

    protected FTCommunication(Satin s) {
        this.s = s;
    }

    protected void electClusterCoordinator() {
        try {
            Registry r = s.comm.ibis.registry();
            s.ft.clusterCoordinatorIdent =
                    r.elect("satin "
                            + Victim.clusterOf(s.comm.ibis.identifier())
                            + " cluster coordinator");
            if (s.ft.clusterCoordinatorIdent.equals(s.comm.ibis.identifier())) {
                /* I am the cluster coordinator */
                s.clusterCoordinator = true;
                ftLogger.info("cluster coordinator for cluster "
                        + Victim.clusterOf(s.comm.ibis.identifier())
                        + " is " + s.ft.clusterCoordinatorIdent);
            }
        } catch (Exception e) {
            ftLogger.fatal("SATIN '" + s.ident + "': Could not start ibis: "
                    + e, e);
            System.exit(1); // Could not start ibis
        }
    }

    /**
     * If the job is being redone (redone flag is set to true), perform a lookup
     * in the global result table. The lookup might fail if the result is thrown
     * away for some reason, or if the node that stored the result has crashed.
     * 
     * @param r
     *            invocation record of the job
     * @return true if an entry was found, false otherwise
     */
    protected boolean askForJobResult(InvocationRecord r) {
        GlobalResultTableValue value = null;
        synchronized (s) {
            value = s.ft.globalResultTable.lookup(r.getStamp());
        }

        if (value == null)
            return false;

        if (value.type == GlobalResultTableValue.TYPE_POINTER) {
            // remote result

            Victim v = null;
            synchronized (s) {
                if (s.deadIbises.contains(value.owner)) {
                    // the one who's got the result has crashed
                    return false;
                }

                grtLogger.debug("SATIN '" + s.ident
                        + "': sending a result request of " + r.getStamp()
                        + " to " + value.owner);

                v = s.victims.getVictim(value.owner);
                if (v == null)
                    return false; // victim has probably crashed

                // put the job in the stolen jobs list.
                r.setStealer(value.owner);
                s.lb.addToOutstandingJobList(r);
            }

            // send a request to the remote node
            WriteMessage m = null;
            try {
                m = v.newMessage();
                m.writeByte(Protocol.RESULT_REQUEST);
                m.writeObject(r.getStamp());
                v.finish(m);
            } catch (IOException e) {
                if (m != null) {
                    m.finish(e);
                }
                grtLogger.warn("SATIN '" + s.ident
                        + "': trying to send RESULT_REQUEST but got "
                        + "exception: " + e, e);
                synchronized (s) {
                    s.outstandingJobs.remove(r);
                }
                return false;
            }
            return true;
        }

        if (value.type == GlobalResultTableValue.TYPE_RESULT) {
            // local result, handle normally
            ReturnRecord rr = value.result;
            rr.assignTo(r);
            r.decrSpawnCounter();
            return true;
        }

        return false;
    }

    protected void sendAbortAndStoreMessage(InvocationRecord r) {
        Satin.assertLocked(s);

        abortLogger.debug("SATIN '" + s.ident
                + ": sending abort and store message to: " + r.getStealer()
                + " for job " + r.getStamp());

        if (s.deadIbises.contains(r.getStealer())) {
            /* don't send abort and store messages to crashed ibises */
            return;
        }

        WriteMessage writeMessage = null;
        try {
            Victim v = s.victims.getVictim(r.getStealer());
            if (v == null)
                return; // probably crashed

            writeMessage = v.newMessage();
            writeMessage.writeByte(Protocol.ABORT_AND_STORE);
            writeMessage.writeObject(r.getParentStamp());
            v.finish(writeMessage);
        } catch (IOException e) {
            if (writeMessage != null) {
                writeMessage.finish(e);
            }
            ftLogger.warn("SATIN '" + s.ident
                    + "': Got Exception while sending abort message: " + e);
            // This should not be a real problem, it is just inefficient.
            // Let's continue...
        }
    }

    // connect upcall functions
    public boolean gotConnection(ReceivePort me, SendPortIdentifier applicant) {
        // accept all connections
        return true;
    }

    // @@@ this is not correct --Rob
    private void handleLostConnection(IbisIdentifier dead) {
        Victim v = null;
        synchronized (s) {
            if (s.deadIbises.contains(dead))
                return;

            s.ft.crashedIbises.add(dead);
            s.deadIbises.add(dead);
            if (dead.equals(s.lb.getCurrentVictim())) {
                s.currentVictimCrashed = true;
                s.lb.setCurrentVictim(null);
            }
            s.ft.gotCrashes = true;
            v = s.victims.remove(dead);
            s.notifyAll();
        }

        if (v != null) {
            v.close();
        }
    }

    private void handleCrash(IbisIdentifier dead) {
        synchronized (s) {
            s.ft.crashedIbises.add(dead);
            if (dead.equals(s.lb.getCurrentVictim())) {
                s.currentVictimCrashed = true;
                s.lb.setCurrentVictim(null);
            }
            s.ft.gotCrashes = true;
            s.notifyAll();
        }
    }

    public void lostConnection(ReceivePort me, SendPortIdentifier johnDoe,
            Throwable reason) {
        if (reason == null) {
            ftLogger.info("SATIN '" + s.ident + "': got lostConnection upcall: "
                    + johnDoe.ibisIdentifier() + " closed connection");
            return;
        }
        ftLogger.info("SATIN '" + s.ident + "': got lostConnection upcall: "
                + johnDoe.ibisIdentifier() + ", reason = " + reason);
        if (connectionUpcallsDisabled) {
            return;
        }
        handleLostConnection(johnDoe.ibisIdentifier());
    }

    public void lostConnection(SendPort me, ReceivePortIdentifier johnDoe,
            Throwable reason) {
        ftLogger.info("SATIN '" + s.ident
                + "': got SENDPORT lostConnection upcall: "
                + johnDoe.ibisIdentifier());
        if (connectionUpcallsDisabled) {
            return;
        }
        handleLostConnection(johnDoe.ibisIdentifier());
    }

    /** The ibis upcall that is called whenever a node joins the computation */
    public void joined(IbisIdentifier joiner) {
        ftLogger.debug("SATIN '" + s.ident + "': got join of " + joiner);

        if (joinThread == null) {
            joinThread = new JoinThread(s);
            joinThread.start();
        }

        joinThread.addJoiner(joiner);
    }

    public void died(IbisIdentifier corpse) {
        ftLogger.debug("SATIN '" + s.ident + "': " + corpse + " died");
        if(corpse.equals(s.ident)) {
            ftLogger.error("SATIN '" + s.ident + "': the registry thinks I have crashed! Exiting.");
            System.exit(1);
        }
        left(corpse);
        handleCrash(corpse);
    }

    public void left(IbisIdentifier leaver) {
        if (leaver.equals(s.ident))
            return;

        joinThread.removeJoiner(leaver);

        ftLogger.debug("SATIN '" + s.ident + "': " + leaver + " left");

        Victim v;

        synchronized (s) {
            // master and cluster coordinators will be reelected
            // only if their crash was confirmed by the nameserver
            if (leaver.equals(s.getMasterIdent())) {
                s.ft.masterHasCrashed = true;
                s.ft.gotCrashes = true;
            }
            if (leaver.equals(s.ft.clusterCoordinatorIdent)) {
                s.ft.clusterCoordinatorHasCrashed = true;
                s.ft.gotCrashes = true;
            }

            s.so.removeSOConnection(leaver);
            s.deadIbises.add(leaver);
            v = s.victims.remove(leaver);
            s.notifyAll();
        }

        if (v != null) {
            v.close();
        }
    }

    public void gotSignal(String signal) {
        if (signal != null && signal.equals("delete")) {
            s.ft.gotDelete = true;
        }
    }

    protected void handleMyOwnJoinJoin() {
        s.so.handleMyOwnJoin();
    }

    protected void handleJoins(IbisIdentifier[] joiners) {
        ftLogger.debug("SATIN '" + s.ident + "': dealing with "
                + joiners.length + " joins");

        s.so.handleJoins(joiners);
        ftLogger.debug("SATIN '" + s.ident + "': SO ports created");

        for (int i = 0; i < joiners.length; i++) {
            IbisIdentifier joiner = joiners[i];

            ftLogger.debug("SATIN '" + s.ident + "': creating sendport");

            SendPort p = null;
            try {
                p = s.comm.ibis.createSendPort(s.comm.portType);
            } catch (Exception e) {
                ftLogger.warn("SATIN '" + s.ident
                        + "': got an exception in Satin.join", e);
                continue;
            }
            ftLogger.debug("SATIN '" + s.ident + "': creating sendport done");

            Victim v = new Victim(joiner, p);

            if(!CONNECTIONS_ON_DEMAND) {
                v.connect();
            }

            synchronized (s) {
                s.victims.add(v);
                s.notifyAll();
            }
            
            ftLogger.debug("SATIN '" + s.ident + "': " + joiner + " JOINED");
        }
    }

    protected void handleAbortAndStore(ReadMessage m) {
        try {
            Stamp stamp = (Stamp) m.readObject();
            synchronized (s) {
                s.ft.addToAbortAndStoreList(stamp);
            }
            // m.finish();
        } catch (Exception e) {
            grtLogger
                    .error(
                            "SATIN '"
                                    + s.ident
                                    + "': got exception while reading abort_and_store: "
                                    + e, e);
        }
    }

    protected void handleResultRequest(ReadMessage m) {
        Victim v = null;
        GlobalResultTableValue value = null;
        Timer handleLookupTimer = null;

        try {
            handleLookupTimer = Timer.createTimer();
            handleLookupTimer.start();

            Stamp stamp = (Stamp) m.readObject();

            IbisIdentifier ident = m.origin().ibisIdentifier();

            m.finish();

            synchronized (s) {
                value = s.ft.globalResultTable.lookup(stamp);
                if (ASSERTS && value == null) {
                    grtLogger.fatal("SATIN '" + s.ident
                            + "': EEK!!! no requested result in the table: "
                            + stamp);
                    System.exit(1); // Failed assertion
                } else if (ASSERTS && value.type == GlobalResultTableValue.TYPE_POINTER) {
                    grtLogger.fatal("SATIN '" + s.ident + "': EEK!!! " + ident
                            + " requested a result: " + stamp
                            + " which is stored on another node: " + value);
                    System.exit(1); // Failed assertion
                }

                v = s.victims.getVictim(ident);
            }
            if (v == null) {
                ftLogger.debug("SATIN '" + s.ident
                        + "': the node requesting a result died");
                handleLookupTimer.stop();
                s.stats.handleLookupTimer.add(handleLookupTimer);
                return;
            }
            value.result.setStamp(stamp);
            WriteMessage w = null;
            try {
                w = v.newMessage();
                w.writeByte(Protocol.JOB_RESULT_NORMAL);
                w.writeObject(value.result);
                v.finish(w);
            } catch(IOException e) {
                if (w != null) {
                    w.finish(e);
                }
                throw e;
            }
        } catch (Exception e) {
            grtLogger.error("SATIN '" + s.ident
                    + "': trying to send result back, but got exception: " + e,
                    e);
        }
        handleLookupTimer.stop();
        s.stats.handleLookupTimer.add(handleLookupTimer);
    }

    protected void handleResultPush(ReadMessage m) {
        grtLogger.info("SATIN '" + s.ident + ": handle result push");
        try {
            @SuppressWarnings("unchecked")
            Map<Stamp, GlobalResultTableValue> results =
                    (Map<Stamp, GlobalResultTableValue>) m.readObject();
            synchronized (s) {
                s.ft.globalResultTable.updateAll(results);
            }
        } catch (Exception e) {
            grtLogger.error("SATIN '" + s.ident
                    + "': trying to read result push, but got exception: " + e,
                    e);
        }

        grtLogger.info("SATIN '" + s.ident + ": handle result push finished");
    }

    protected void disableConnectionUpcalls() {
        connectionUpcallsDisabled = true;
    }

    protected void pushResults(Victim victim,
            Map<Stamp, GlobalResultTableValue> toPush) {
        if (toPush.size() == 0)
            return;

        WriteMessage m = null;
        try {
            m = victim.newMessage();
            m.writeByte(Protocol.RESULT_PUSH);
            m.writeObject(toPush);
            long numBytes = victim.finish(m);
            grtLogger.debug("SATIN '" + s.ident + "': " + numBytes
                    + " bytes pushed");
        } catch (IOException e) {
            if (m != null) {
                m.finish(e);
            }
            grtLogger.info("SATIN '" + s.ident + "': error pushing results "
                    + e);
        }
    }

    public void electionResult(String electionName, IbisIdentifier winner) {
        // TODO Use this result?
    }
}