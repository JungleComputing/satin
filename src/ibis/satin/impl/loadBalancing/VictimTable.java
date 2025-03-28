/* $Id$ */

package ibis.satin.impl.loadBalancing;

import java.io.IOException;
import java.util.HashMap;
import java.util.Random;
import java.util.Vector;

import ibis.ipl.IbisIdentifier;
import ibis.ipl.SendPort;
import ibis.satin.impl.Config;
import ibis.satin.impl.Satin;

public final class VictimTable implements Config {
    private Random random = new Random();

    private Vector<Victim> victims = new Vector<>();
    // all victims grouped by cluster
    private Vector<Cluster> clusters = new Vector<>();

    private Cluster thisCluster;

    private HashMap<String, Cluster> clustersHash = new HashMap<>();

    private HashMap<IbisIdentifier, Victim> ibisHash = new HashMap<>();

    private Satin satin;

    public VictimTable(Satin s) {
        this.satin = s;
        thisCluster = new Cluster(Victim.clusterOf(s.ident));
        clusters.add(thisCluster);
        clustersHash.put(Victim.clusterOf(s.ident), thisCluster);
    }

    public void add(Victim v) {
        Satin.assertLocked(satin);

        if (victims.contains(v)) {
            commLogger.info("SATIN '" + satin.ident + "': victim " + v + " was already added");
            return;
        }
        victims.add(v);
        ibisHash.put(v.getIdent(), v);

        Cluster c = clustersHash.get(Victim.clusterOf(v.getIdent()));
        if (c == null) { // new cluster
            c = new Cluster(v); // v is automagically added to this cluster
            clusters.add(c);
            clustersHash.put(Victim.clusterOf(v.getIdent()), c);
        } else {
            c.add(v);
        }
    }

    public Victim remove(IbisIdentifier ident) {
        Satin.assertLocked(satin);
        Victim v = new Victim(ident, null);
        ibisHash.remove(ident);

        int i = victims.indexOf(v);
        return remove(i);
    }

    public Victim remove(int i) {
        Satin.assertLocked(satin);

        if (i < 0 || i >= victims.size()) {
            return null;
        }

        Victim v = victims.remove(i);

        Cluster c = clustersHash.get(Victim.clusterOf(v.getIdent()));
        c.remove(v);
        ibisHash.remove(v.getIdent());

        if (c.size() == 0) {
            clustersHash.remove(Victim.clusterOf(v.getIdent()));
        }

        return v;
    }

    public int size() {
        // Satin.assertLocked(satin);
        return victims.size();
    }

    public Victim getVictim(int i) {
        Satin.assertLocked(satin);
        if (ASSERTS && i < 0 || i >= victims.size()) {
            commLogger.info(
                    "SATIN '" + satin.ident + "': trying to read a non-existing victim id: " + i + ", there are " + victims.size() + " victims");
            return null;
        }
        return victims.get(i);
    }

    public Victim getVictimNonBlocking(IbisIdentifier ident) {
        Satin.assertLocked(satin);
        return ibisHash.get(ident);
    }

    public Victim getRandomVictim() {
        Victim v = null;
        int index;

        Satin.assertLocked(satin);

        if (victims.size() == 0) {
            // can happen with open world, no others have joined yet.
            return null;
        }

        index = random.nextInt(victims.size());
        v = victims.get(index);

        return v;
    }

    /**
     * returns null if there are no other nodes in this cluster
     */
    public Victim getRandomLocalVictim() {
        Victim v = null;
        int index;
        int clusterSize = thisCluster.size();

        Satin.assertLocked(satin);

        if (clusterSize == 0) {
            return null;
        }

        index = random.nextInt(clusterSize);
        v = thisCluster.get(index);

        return v;
    }

    /**
     * Returns null if there are no remote victims i.e., there's only one cluster
     */
    public Victim getRandomRemoteVictim() {
        Victim v = null;
        int vIndex, cIndex;
        int remoteVictims;
        Cluster c;

        Satin.assertLocked(satin);

        if (ASSERTS && clusters.get(0) != thisCluster) {
            commLogger.error("SATIN '" + satin.ident + "': getRandomRemoteVictim: firstCluster != me");
            System.exit(1); // Failed assertion
        }

        remoteVictims = victims.size() - thisCluster.size();

        if (remoteVictims == 0) {
            return null;
        }

        vIndex = random.nextInt(remoteVictims);

        // find the cluster and index in the cluster for the victim
        cIndex = 1;
        c = clusters.get(cIndex);
        while (vIndex >= c.size()) {
            vIndex -= c.size();
            cIndex += 1;
            c = clusters.get(cIndex);
        }

        v = c.get(vIndex);

        return v;
    }

    public void print(java.io.PrintStream out) {
        Satin.assertLocked(satin);

        out.println("victimtable on " + satin + ", size is " + victims.size());

        for (Victim victim : victims) {
            out.println("   " + victim);
        }
    }

    public boolean contains(IbisIdentifier ident) {
        Satin.assertLocked(satin);
        return ibisHash.get(ident) != null;
    }

    public IbisIdentifier[] getIbises() {
        Satin.assertLocked(satin);
        IbisIdentifier[] tmp = new IbisIdentifier[victims.size()];
        for (int i = 0; i < tmp.length; i++) {
            tmp[i] = victims.get(i).getIdent();
        }

        return tmp;
    }

    // retry a couple of times, then assume it will join at some point, so just
    // add it.
    public Victim getVictim(IbisIdentifier id) {
        Satin.assertLocked(satin);
        Victim v = null;

        // long start = System.currentTimeMillis();

        // do {
        v = getVictimNonBlocking(id);
        if (v != null) {
            return v;
        }

        // Added test for a as yet unprocessed dead ibis. (Ceriel)
        // It gets removed from the victim table as soon as the died
        // upcall is received, but further processing is not done
        // immediately. Anyway, we don't want to create a new victim
        // for it.
        if (satin.deadIbises.contains(id)) {
            return null;
        }

        /*
         * try { satin.wait(250); } catch (Exception e) { // Ignore. } } while
         * (System.currentTimeMillis() - start < 1000);
         */

        // @@@ TODO: this only works with SOBCAST disabled!
        ftLogger.info("SATIN '" + satin.ident + "': could not get victim for " + id + " creating victim on demand");

        SendPort p = null;
        try {
            p = satin.comm.ibis.createSendPort(satin.comm.portType);
        } catch (Exception e) {
            ftLogger.warn("SATIN '" + satin.ident + "': got an exception in getVictim", e);
        }
        ftLogger.debug("SATIN '" + satin.ident + "': creating sendport done");

        Victim newV = null;
        if (p != null) {
            newV = new Victim(id, p);
            add(newV);
        } else {
            try {
                satin.comm.ibis.registry().maybeDead(id);
            } catch (IOException e) {
                ftLogger.warn("SATIN '" + satin.ident + "': got exception in maybeDead", e);
            }
        }
        return newV;
    }

    public Victim[] victims() {
        Satin.assertLocked(satin);
        return victims.toArray(new Victim[size()]);
    }
}
