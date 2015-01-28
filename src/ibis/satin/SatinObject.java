/* $Id$ */

package ibis.satin;

import ibis.satin.impl.Satin;

import java.io.Serializable;

/**
 * This is the magic class that should be extended by objects that implement
 * spawnable methods. When the program is not rewritten by the Satin frontend,
 * the methods described here are basically no-ops, and the program will run
 * sequentially. When the program is rewritten by the Satin frontend, calls to
 * spawnable methods, and calls to {@link #sync()}and {@link #abort()}will be
 * rewritten.
 */
public class SatinObject implements java.io.Serializable {

    /**
     * Generated
     */
    private static final long serialVersionUID = -3958487192660018892L;

    /**
     * Prevents constructor from being public.
     */
    protected SatinObject() {
	// nothing here
    }

    /**
     * Waits until all spawned methods in the current method are finished.
     */
    public void sync() {
	/* do nothing, bytecode is rewritten to handle this */
    }

    /**
     * Recursively aborts all methods that were spawned by the current method
     * and all methods spawned by the aborted methods.
     */
    public void abort() {
	/* do nothing, bytecode is rewritten to handle this */
    }

    /**
     * Pauses Satin operation. This method can optionally be called before a
     * large sequential part in a program. This will temporarily pause Satin's
     * internal load distribution strategies to avoid communication overhead
     * during sequential code.
     */
    public static void pause() {
	Satin.pause();
    }

    /**
     * Resumes Satin operation. This method can optionally be called after a
     * large sequential part in a program.
     */
    public static void resume() {
	Satin.resume();
    }

    /**
     * Returns whether it might be useful to spawn more Satin jobs. If there is
     * enough work in the system to keep all processors busy, this method
     * returns false.
     * 
     * @return <code>true</code> if it might be useful to spawn more
     *         invocations, false if there is enough work in the system.
     */
    public static boolean needMoreJobs() {
	return Satin.needMoreJobs();
    }

    /**
     * Returns whether the current Satin job was generated by the machine it is
     * running on. Satin jobs can be distributed to remote machines by the Satin
     * runtime system, in which case this method returns false.
     * 
     * @return <code>true</code> if the current invocation is not stolen from
     *         another processor.
     */
    public static boolean localJob() {
	return Satin.localJob();
    }

    /**
     * Creates and returns a deep copy of the specified object.
     * 
     * @param o
     *            the object to be copied
     * @return the copy.
     */
    public static Serializable deepCopy(Serializable o) {
	return Satin.deepCopy(o);
    }
}
