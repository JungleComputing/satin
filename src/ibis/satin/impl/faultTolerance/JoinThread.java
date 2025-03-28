/*
 * Created on Jun 1, 2006
 */
package ibis.satin.impl.faultTolerance;

import java.util.ArrayList;

import ibis.ipl.IbisIdentifier;
import ibis.satin.impl.Config;
import ibis.satin.impl.Satin;

class JoinThread extends Thread implements Config {
    private static final boolean CACHE_JOINS = true;

    private Satin s;

    private ArrayList<IbisIdentifier> joiners = new ArrayList<>();

    JoinThread(Satin s) {
        this.s = s;
        setDaemon(true);
        setName("satin join thread");
    }

    private void handlePendingJoins() {
        IbisIdentifier[] j = null;
        synchronized (this) {
            if (joiners.size() != 0) {
                j = joiners.toArray(new IbisIdentifier[0]);
                joiners.clear();
                s.ft.ftComm.handleJoins(j);
                notifyAll();
            }
        }
    }

    @Override
    public void run() {
        while (true) {
            handlePendingJoins();

            // Sleep 1 second, maybe more nodes have joined within this second.
            // we can aggregate the port lookups for all those joiners.
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                // ignored
            }
        }
    }

    protected synchronized void waitForEarlierJoins() {
        while (joiners.size() != 0) {
            try {
                wait();
            } catch (Exception e) {
                // ignored
            }
        }
    }

    protected synchronized void addJoiner(IbisIdentifier joiner) {
        if (CACHE_JOINS) {
            if (joiner.equals(s.ident)) {
                ftLogger.debug("SATIN '" + s.ident + "': this is me, waiting for earlier joins");
                waitForEarlierJoins();
                ftLogger.debug("SATIN '" + s.ident + "': waiting for earlier joins done");
                s.ft.ftComm.handleMyOwnJoinJoin();
                return;
            }

            joiners.add(joiner);
        } else {
            if (joiner.equals(s.ident)) {
                ftLogger.debug("SATIN '" + s.ident + "': got my own join");
                s.ft.ftComm.handleMyOwnJoinJoin();
                return;
            }
            IbisIdentifier[] j = new IbisIdentifier[1];
            j[0] = joiner;
            s.ft.ftComm.handleJoins(j);
        }
    }

    protected synchronized void removeJoiner(IbisIdentifier leaver) {
        if (CACHE_JOINS) {
            joiners.remove(leaver);
        }
    }
}
