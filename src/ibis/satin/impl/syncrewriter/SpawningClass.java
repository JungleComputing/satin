package ibis.satin.impl.syncrewriter;

import ibis.satin.impl.syncrewriter.util.Debug;

import java.util.ArrayList;

import org.apache.bcel.classfile.JavaClass;
import org.apache.bcel.classfile.Method;
import org.apache.bcel.generic.ClassGen;


class SpawningClass extends ClassGen {

    private static final long serialVersionUID = 1L;
    
    private SpawnSignature[] spawnSignatures;
    private Debug d;


    /* package methods */


    SpawningClass (JavaClass javaClass, SpawnSignature[] allSpawnSignatures, Debug d) throws NoSpawningClassException {
	super(javaClass);

	if (!javaClass.isClass()) throw new NoSpawningClassException();

	this.d = d;
	this.spawnSignatures = getSpawnSignatures(allSpawnSignatures);

	if (this.spawnSignatures.length == 0) throw new NoSpawningClassException();
    }


    SpawnSignature[] getSpawnSignatures() {
	return spawnSignatures;
    }


    void rewrite(Analyzer analyzer) throws ClassRewriteFailure {
	boolean throwRewriteFailure = false;

	for (SpawnSignature spawnSignature : spawnSignatures) {
	    try {
		rewriteForSpawnSignature(spawnSignature, analyzer);
	    }
	    catch (MethodRewriteFailure e) {
		throwRewriteFailure = true;
	    }
	}
	if (throwRewriteFailure) throw new ClassRewriteFailure();
    }




    /* private methods */

    /* Rewrite for a specific spawn signature.
    */
    private void rewriteForSpawnSignature(SpawnSignature spawnSignature, Analyzer analyzer) 
	throws MethodRewriteFailure {
	boolean throwRewriteFailure = false;

	d.log(0, "rewriting for %s\n", spawnSignature);

	int indexSync = getConstantPool().addMethodref(spawnSignature.getClassName(), 
		"sync", "()V");

	Method[] methods = getMethods();

	for (Method method : methods) {
	    try {
		SpawningMethod spawningMethod = new 
		    SpawningMethod(method, getClassName(), getConstantPool(),
			    spawnSignature, indexSync, 
			    new Debug(d.turnedOn(), d.getStartLevel()+2));
		d.log(1, "%s calls %s\n", method.getName(), spawnSignature);
		spawningMethod.rewrite(analyzer);
		removeMethod(method);
		addMethod(spawningMethod.getMethod());
	    }
	    catch (NoSpawningMethodException e) {
		d.log(1, "%s doesn't call %s\n", method.getName(), spawnSignature);
		// spawnSignature is not called
	    }
	    catch (MethodRewriteFailure e) {
		d.error("Failed to rewrite %s for spawn signature: %s\n",
			method.getName(), spawnSignature);
		throwRewriteFailure = true;
	    }
	    catch (AssumptionFailure e) {
		d.error("Failed to rewrite %s for spawn signature: %s\n",
			method.getName(), spawnSignature);
		d.error(e.getMessage());
		throwRewriteFailure = true;
	    }
	}

	if (throwRewriteFailure) throw new MethodRewriteFailure();
	d.log(0, "rewritten for %s\n", spawnSignature);
    }


    /* it is true that creating a spawning method happens twice, once to find
     * the spawnsignatures for this class and once for rewriting the methods.
     * It seems a better idea to just store spawningMethods in the beginning
     * and rewrite that. However, every time you rewrite a spawningMethod for
     * 'spawnSignature1' and then you would have to update all these
     * spawningMethods for the method that has been rewritten, in case you
     * are going to rewrite for 'spawnSignature2'. Rewriting is an iterative
     * process:
     *
     * spawningMetod = new SpawningMethod(method)
     * this.remove(method)
     * spawningMethod.rewriteFor(spawnSignature1);
     * this.add(spawningMethod.getMethod());
     *
     * spawningMethod = new SpawningMethod(method) // this method is updated
     * this.remove(method)
     * spawningMethod.rewriteFor(spawnSignature1);
     * this.add(spawningMethod.getMethod());
     *
     * Therefore, the easiest option is chosen: Just try to create a
     * SpawningMethod for some spawnSignature and record the spawnSignature.
     */
    private void getSpawnSignatures(ArrayList<SpawnSignature> spawnSignatures, SpawnSignature spawnSignature) {
	Method[] methods = getMethods();

	for (Method method : methods) {
	    try {
		// SpawningMethod spawningMethod = 
	        new SpawningMethod(method, getClassName(), getConstantPool(),
	                spawnSignature, -1 /* doesn't matter for this time */, 
	                new Debug());
		if (!spawnSignatures.contains(spawnSignature)) spawnSignatures.add(spawnSignature);
	    }
	    catch (NoSpawningMethodException e) {
		// spawnSignature is not called
	    }
	    catch (AssumptionFailure e) {
		// ignore for now, will be caught the second time
	    }
	}
    }


    private SpawnSignature[] getSpawnSignatures(SpawnSignature[] allSpawnSignatures) {
	ArrayList<SpawnSignature> spawnSignatures = new ArrayList<SpawnSignature>();

	for (SpawnSignature spawnSignature : allSpawnSignatures) {
	    getSpawnSignatures(spawnSignatures, spawnSignature);
	}

	SpawnSignature[] spawnSignaturesArray = new SpawnSignature[spawnSignatures.size()];
	return spawnSignatures.toArray(spawnSignaturesArray);
    }
}
