package ibis.satin.impl.syncrewriter.analyzer;

import ibis.satin.impl.syncrewriter.analyzer.controlflow.*; 
import ibis.satin.impl.syncrewriter.controlflow.*;

import ibis.satin.impl.syncrewriter.util.Debug;

//import ibis.satin.impl.syncrewriter.bcel.Util;

import java.util.ArrayList;

import ibis.satin.impl.syncrewriter.Analyzer; 
import ibis.satin.impl.syncrewriter.SyncInsertionProposalFailure; 
import ibis.satin.impl.syncrewriter.SpawnableCall; 
import ibis.satin.impl.syncrewriter.SpawnableMethod;

import org.apache.bcel.generic.InstructionHandle; 
import org.apache.bcel.verifier.structurals.InstructionContext; 
import org.apache.bcel.generic.InstructionList; 
import org.apache.bcel.generic.MethodGen; 
import org.apache.bcel.generic.LoadInstruction; 
import org.apache.bcel.generic.ConstantPoolGen; 
import org.apache.bcel.generic.Instruction; 
import org.apache.bcel.generic.ALOAD;
import org.apache.bcel.generic.AASTORE; 
import org.apache.bcel.generic.BASTORE;
import org.apache.bcel.generic.BranchInstruction; 
import org.apache.bcel.generic.CASTORE; 
import org.apache.bcel.generic.DASTORE; 
import org.apache.bcel.generic.FASTORE; 
import org.apache.bcel.generic.IASTORE; 
import org.apache.bcel.generic.LASTORE; 
import org.apache.bcel.generic.SASTORE;




public class ControlFlow implements Analyzer {


    private class PathPartOfLoopException extends Exception { 
	private static final String MESSAGE = "Sync insertion is part of a loop"; 
	private PathPartOfLoopException() { 
	    super(MESSAGE); 
	} 
    }


    private class ResultNotLoadedException extends Exception { 
	private static final String MESSAGE = "The result is not loaded"; 
	private ResultNotLoadedException() { 
	    super(MESSAGE); 
	} 
    }



    private Debug d;



    public InstructionHandle[] proposeSyncInsertion(SpawnableMethod
	    spawnableMethod, Debug d) throws SyncInsertionProposalFailure {

	this.d = d;

	if (needsImmediateSyncInsertion(spawnableMethod)) { 
	    d.warning("Some results are not stored, defaulting to no parallelism\n");
	    return getImmediateSyncs(spawnableMethod); 
	}

	d.log(0, "proposing basic blocks\n"); 
	BasicBlock[] proposedBasicBlocks
	    = proposeBasicBlocks(spawnableMethod); 
	d.log(0, "proposed basic blocks:\n"); d.log(1, "%s\n", toString(proposedBasicBlocks));

	return getEarliestLoadInstructions(proposedBasicBlocks,
		spawnableMethod);

	    }







    private void removeSpawnableCallsBefore(InstructionHandle ih,
	    ArrayList<SpawnableCall> spawnableCalls, 
	    BasicBlock basicBlock) {

	ArrayList<SpawnableCall> spawnableCallsClone = new
	    ArrayList<SpawnableCall>(spawnableCalls); 
	for (SpawnableCall spawnableCall : spawnableCallsClone) { 
	    InstructionHandle spawnInstructionHandle = 
		spawnableCall.getInvokeInstruction();
	    if (basicBlock.contains(spawnInstructionHandle)) { 
		if (spawnInstructionHandle.getPosition() < ih.getPosition()) {
		    spawnableCalls.remove(spawnableCall); 
		} 
	    } else {
		spawnableCalls.remove(spawnableCall); 
	    } 
	} 
    }



    /* returns the load instruction that goes with the spawnable call or the
     * last load instruction, if there is no load instruction for this
     * spawnable call in the instructions.
     */
    private InstructionHandle getLoadInstruction(ArrayList<InstructionContext> instructions, SpawnableCall spawnableCall, 
	    SpawnableMethod spawnableMethod) throws ResultNotLoadedException {
	int startIndex = 0;
	for (int i = 0; i < instructions.size(); i++) {
	    InstructionHandle ih = instructions.get(i).getInstruction();
	    if (ih.equals(spawnableCall.getInvokeInstruction())) {
		startIndex = i;
		break;
	    }
	}

	for (int i = startIndex; i < instructions.size(); i++) {
	    InstructionHandle ih = instructions.get(i).getInstruction();
	    try {
		LoadInstruction loadInstruction = 
		    (LoadInstruction) (ih.getInstruction());
		if (spawnableCall.storesIn(loadInstruction.getIndex()) && 
			!spawnableMethod.isUsedForArrayStore(ih) &&
			!spawnableMethod.isUsedForPutField(ih)) {  
		    return ih;
			}
	    }
	    catch (ClassCastException e) {
	    }
	}
	throw new ResultNotLoadedException();
    }





    /* Get the earliest load instruction of the results of the spawnable calls.
     *
     * result1 = spawnableCall();
     * result2 = spawnableCall();
     *
     * read(result2); <---- this is returned
     * read(result1);
     */
    private InstructionHandle getEarliestLoadInstruction(ArrayList<InstructionContext> instructions, 
	    ArrayList<SpawnableCall> spawnableCalls, SpawnableMethod spawnableMethod) throws ResultNotLoadedException {

	InstructionHandle earliestLoadInstruction = null;

	d.log(2, "looking for load instruction for:\n"); 
	for (SpawnableCall spawnableCall : spawnableCalls) {
	    d.log(3, "spawnable call: %s\n", spawnableCall);
	    try {
		InstructionHandle loadInstruction = getLoadInstruction(instructions, spawnableCall, spawnableMethod);
		d.log(4, "found load instruction %s on position %d\n", loadInstruction, loadInstruction.getPosition());
		if (earliestLoadInstruction == null 
			|| loadInstruction.getPosition() < earliestLoadInstruction.getPosition()) {
		    earliestLoadInstruction = loadInstruction;
			}
	    }
	    catch (ResultNotLoadedException e) {
		d.log(4, "result of spawnable call not loaded\n");
	    }
	}
	if (earliestLoadInstruction == null) {
	    d.log(2, "didn't find loadinstructions\n");
	    throw new ResultNotLoadedException();
	}
	d.log(2, "earliest load instruction: %s\n", earliestLoadInstruction);
	return earliestLoadInstruction;
    }


    private void addLoadsWithSpawnableCallsAfter(InstructionHandle earliestLoad, 
	    BasicBlock basicBlock, SpawnableMethod spawnableMethod,
	    ArrayList<SpawnableCall> spawnableCalls, 
	    ArrayList<InstructionHandle> earliestLoads) {

	ArrayList<SpawnableCall> spawnableCallsCopy = new ArrayList<SpawnableCall>(spawnableCalls);

	removeSpawnableCallsBefore(earliestLoad, spawnableCallsCopy, basicBlock);

	while (spawnableCallsCopy.size() > 0) {
	    d.log(1, "there are spawnable calls after the earliest load, trying to get the earliest load for these spawnable calls\n");
	    try {
		earliestLoad = getEarliestLoadInstruction(basicBlock.getInstructions(), 
			spawnableCallsCopy, spawnableMethod);
		earliestLoads.add(earliestLoad);
		removeSpawnableCallsBefore(earliestLoad, spawnableCallsCopy, basicBlock);
	    }
	    catch (ResultNotLoadedException e) {
		d.log(1, "there are no loads associated with these last spawnable calls\n");
		spawnableCallsCopy.removeAll(spawnableCallsCopy);
	    }
	}
    }



    private ArrayList<InstructionHandle> tryEarliestLoadInstructions(BasicBlock basicBlock, SpawnableMethod spawnableMethod) {
	ArrayList<SpawnableCall> spawnableCalls = spawnableMethod.getSpawnableCalls();
	ArrayList<InstructionHandle> earliestLoads = new ArrayList<InstructionHandle>();

	try {
	    d.log(1, "trying to get the earliest load associated with spawnable calls in basic block %d\n", basicBlock.getIndex());
	    InstructionHandle earliestLoad =  getEarliestLoadInstruction(basicBlock.getInstructions(), 
		    spawnableCalls, spawnableMethod);
	    earliestLoads.add(earliestLoad);
	    d.log(1, "succeeded to get the earliest load\n");

	    addLoadsWithSpawnableCallsAfter(earliestLoad, basicBlock, spawnableMethod, spawnableCalls, earliestLoads);
	}
	catch (ResultNotLoadedException e) {
	    InstructionHandle lastInstruction = basicBlock.getEnd().getInstruction();
	    d.log(1, "result is not loaded in this basic block, defaulting to last instruction of the basic block\n");
	    earliestLoads.add(lastInstruction);
	    return earliestLoads;
	}

	return earliestLoads;
    }





    private InstructionHandle[] getEarliestLoadInstructions(BasicBlock[] basicBlocks, SpawnableMethod spawnableMethod) {
	ArrayList<InstructionHandle> bestInstructionHandles = new ArrayList<InstructionHandle>();
	InstructionHandle[] result = new InstructionHandle[0];

	d.log(0, "trying to get the earliest load instructions\n"); 
	for (int i = 0; i < basicBlocks.length; i++) {
	    bestInstructionHandles.addAll(tryEarliestLoadInstructions(basicBlocks[i], spawnableMethod));
	}

	return bestInstructionHandles.toArray(result);
    }













    /* proposing a basic block */

    private BasicBlock getLastBasicBlockNotPartOfLoop(BasicBlockGraph basicBlockGraph, Path path) throws PathPartOfLoopException {
	for (int i = path.size() - 1; i >= 0; i--) {
	    BasicBlock basicBlock = path.get(i);
	    if (!basicBlockGraph.isPartOfLoop(basicBlock.getIndex())) {
		return basicBlock;
	    }
	}
	throw new PathPartOfLoopException();
    }


    private String toString(BasicBlock[] basicBlocks) {
	if (basicBlocks.length == 0) return "";
	StringBuilder sb = new StringBuilder();
	for (int i = 0; i < basicBlocks.length - 1; i++) {
	    sb.append(basicBlocks[i].getIndex());
	    sb.append(", ");
	}
	sb.append(basicBlocks[basicBlocks.length - 1].getIndex());
	return sb.toString();
    }


    private BasicBlock getBestBasicBlock(BasicBlockGraph basicBlockGraph, Path path) {
	try {
	    BasicBlock lastBasicBlockNotPartOfLoop = 
		getLastBasicBlockNotPartOfLoop(basicBlockGraph, path);
	    d.log(1, "the last basic block not part of a loop: %d\n", lastBasicBlockNotPartOfLoop.getIndex());
	    return lastBasicBlockNotPartOfLoop;
	}
	catch (PathPartOfLoopException e) {
	    BasicBlock lastBasicBlock = path.getLastBasicBlock(); 
	    d.warning("taking last basic block %d, but it is part of a loop\n", lastBasicBlock.getIndex());
	    return lastBasicBlock;
	}
    }


    private ArrayList<BasicBlock> getBestBasicBlocks(ArrayList<Path> storeLoadPaths, BasicBlockGraph basicBlockGraph) {
	ArrayList<BasicBlock> basicBlocks = new ArrayList<BasicBlock>();
	for (int i = 0; i < storeLoadPaths.size(); i++) {
	    basicBlocks.add(getBestBasicBlock(basicBlockGraph, storeLoadPaths.get(i)));
	}

	return basicBlocks;
    }



    private BasicBlock[] getBestBasicBlocks(SpawnableCallAnalysis[] spawnableCallAnalyses, BasicBlockGraph basicBlockGraph) {
	BasicBlock[] basicBlocks = new BasicBlock[spawnableCallAnalyses.length];
	for (int i = 0; i < basicBlocks.length; i++) {
	    basicBlocks[i] = getBestBasicBlock(basicBlockGraph, spawnableCallAnalyses[i].getLatestCommonSubPath());
	}

	return basicBlocks;
    }


    private String toString(Path[] paths) {
	if (paths.length == 0) return "[]";
	StringBuilder sb = new StringBuilder("[");
	for (int i = 0; i < paths.length - 1; i++) {
	    sb.append(paths[i]);
	    sb.append(", ");
	}
	sb.append(paths[paths.length - 1]);
	sb.append("]");
	return sb.toString();
    }




    private Path getLatestCommonSubPath(SpawnableCallAnalysis[] spawnableCallAnalyses) 
	throws SyncInsertionProposalFailure {
	Path[] latestCommonSubPaths = new Path[spawnableCallAnalyses.length];
	for (int i = 0; i < spawnableCallAnalyses.length; i++) {
	    latestCommonSubPaths[i] = spawnableCallAnalyses[i].getLatestCommonSubPath();
	}

	d.log(1, "the latest common subpaths for all spawnable calls combined:\n");
	d.log(2, toString(latestCommonSubPaths));

	Path latestCommonSubPath = Path.getLatestCommonSubPath(latestCommonSubPaths);

	d.log(1, "the final latest subpath from all spawnable calls combined:\n");
	d.log(2, "%s\n", latestCommonSubPath);
	return latestCommonSubPath;

	/*
	   d.log(1, "the latest common subpath for all spawnable calls:\n");
	   d.log(2, "%s\n", latestCommonSubPath);
	   */


    }

    private BasicBlock[] getLastBasicBlocks(ArrayList<StoreLoadPath> paths) {
	BasicBlock[] basicBlocks = new BasicBlock[paths.size()];
	for (int i = 0; i < paths.size(); i++) {
	    basicBlocks[i] = paths.get(i).getLastBasicBlock();
	}

	return basicBlocks;
    }

    private void addPaths(ArrayList<Path> storeLoadPaths, ArrayList<Path> allStoreLoadPaths) {
	for (Path storeLoadPath : storeLoadPaths) {
	    if (!allStoreLoadPaths.contains(storeLoadPath)) {
		allStoreLoadPaths.add(storeLoadPath);
	    }
	}
    }


    private void addStoreLoadPaths(ArrayList<StoreLoadPath> storeLoadPaths, ArrayList<Path> allStoreLoadPaths) {
	for (StoreLoadPath storeLoadPath : storeLoadPaths) {
	    if (!allStoreLoadPaths.contains(storeLoadPath)) {
		allStoreLoadPaths.add(storeLoadPath);
	    }
	}
    }


    private ArrayList<Path> getStoreLoadPaths(SpawnableCallAnalysis[] spawnableCallAnalyses) {
	ArrayList<Path> allStoreLoadPaths = new ArrayList<Path>();
	for (SpawnableCallAnalysis spawnableCallAnalysis : spawnableCallAnalyses) {
	    ArrayList<StoreLoadPath> storeLoadPaths = spawnableCallAnalysis.getStoreLoadPaths();
	    if (storeLoadPaths.size() == 0) {
		addPaths(spawnableCallAnalysis.getEndingPaths(), allStoreLoadPaths);
	    }
	    else {
		addStoreLoadPaths(storeLoadPaths, allStoreLoadPaths);
	    }
	}

	return allStoreLoadPaths;
    }


    private SpawnableCallAnalysis[] getSpawnableCallAnalyses(ArrayList<SpawnableCall> spawnableCalls,
	    BasicBlockGraph basicBlockGraph) {
	SpawnableCallAnalysis[] spawnableCallAnalyses = new SpawnableCallAnalysis[spawnableCalls.size()];

	for (int i = 0; i < spawnableCalls.size(); i++) {
	    spawnableCallAnalyses[i] = new SpawnableCallAnalysis(spawnableCalls.get(i), basicBlockGraph, new Debug(d.turnedOn(), d.getStartLevel() + 1));
	}

	return spawnableCallAnalyses;
    }


    private boolean hasBasicBlockBefore(BasicBlock endPointPath, Path storeLoadPath, ArrayList<BasicBlock> basicBlocks) {
	for (BasicBlock basicBlock : basicBlocks) {
	    if (storeLoadPath.containsBefore(basicBlock, endPointPath)) {
		return true;
	    }
	}
	return false;
    }


    private ArrayList<BasicBlock> filter(ArrayList<BasicBlock> basicBlocks, ArrayList<Path> storeLoadPaths) {
	ArrayList<BasicBlock> filteredBasicBlocks = new ArrayList<BasicBlock>();
	BasicBlock[] basicBlocksArray = new BasicBlock[basicBlocks.size()];
	basicBlocksArray = basicBlocks.toArray(basicBlocksArray);

	for (int i = 0; i < basicBlocks.size(); i++) {
	    if (hasBasicBlockBefore(basicBlocks.get(i), storeLoadPaths.get(i), basicBlocks)) {
		basicBlocksArray[i] = null;
	    }
	}
	for (int i = 0; i < basicBlocksArray.length; i++) {
	    if (basicBlocksArray[i] != null) {
		filteredBasicBlocks.add(basicBlocksArray[i]);
	    }
	}


	return filteredBasicBlocks;
    }


    private ArrayList<BasicBlock> removeDuplicates(ArrayList<BasicBlock> basicBlocks) {
	ArrayList<BasicBlock> withoutDuplicates = new ArrayList<BasicBlock>();
	for (BasicBlock basicBlock : basicBlocks) {
	    if (!withoutDuplicates.contains(basicBlock)) {
		withoutDuplicates.add(basicBlock);
	    }
	}
	return withoutDuplicates;
    }



    private BasicBlock[] proposeBasicBlocks(SpawnableMethod spawnableMethod) 
	throws SyncInsertionProposalFailure {

	ArrayList<SpawnableCall> spawnableCalls = 
	    spawnableMethod.getSpawnableCalls();
	BasicBlockGraph basicBlockGraph = new BasicBlockGraph(spawnableMethod);

	d.log(1, "analyzing spawnable calls\n");
	SpawnableCallAnalysis[] spawnableCallAnalyses = getSpawnableCallAnalyses(spawnableCalls, basicBlockGraph);
	d.log(1, "analyzed spawnable calls\n");

	d.log(1, "all store load paths:\n");
	ArrayList<Path> allStoreLoadPaths = getStoreLoadPaths(spawnableCallAnalyses);
	d.log(2, "%s\n", allStoreLoadPaths);

	if (allStoreLoadPaths.size() == 1) {
	    BasicBlock[] basicBlocks = new BasicBlock[1];
	    basicBlocks[0] =  getBestBasicBlock(basicBlockGraph, allStoreLoadPaths.get(0));
	    return basicBlocks;
	}
	else {
	    ArrayList<BasicBlock> allBasicBlocks = getBestBasicBlocks(allStoreLoadPaths, basicBlockGraph);
	    ArrayList<BasicBlock> filteredBasicBlocks = filter(allBasicBlocks, allStoreLoadPaths);
	    ArrayList<BasicBlock> duplicatesRemoved = removeDuplicates(filteredBasicBlocks);
	    BasicBlock[] result = new BasicBlock[duplicatesRemoved.size()];
	    return duplicatesRemoved.toArray(result);
	}
    }







    /* handle immediate syncs for when the result of a spawnable call is not stored */

    private InstructionHandle[] getImmediateSyncs(SpawnableMethod spawnableMethod) {
	ArrayList<SpawnableCall> spawnableCalls = spawnableMethod.getSpawnableCalls();
	InstructionHandle[] immediateSyncs = new InstructionHandle[spawnableCalls.size()];
	for (int i = 0; i < immediateSyncs.length; i++) {
	    SpawnableCall spawnableCall = spawnableCalls.get(i);
	    immediateSyncs[i] = spawnableCall.getInvokeInstruction().getNext();
	}
	return immediateSyncs;
    }



    private boolean needsImmediateSyncInsertion(SpawnableMethod spawnableMethod) {
	ArrayList<SpawnableCall> spawnableCalls = spawnableMethod.getSpawnableCalls();
	for (SpawnableCall spawnableCall : spawnableCalls) {
	    if (!spawnableCall.resultIsStored()) {
		return true;
	    }
	}
	return false;
    }
}



/* methods that belong to other classes */



/*
   private ArrayList<InstructionHandle> tryEarliestLoadInstructions2(BasicBlock basicBlock, SpawnableMethod spawnableMethod) {
   ArrayList<SpawnableCall> spawnableCalls = new ArrayList<SpawnableCall>(spawnableMethod.getSpawnableCalls());
   ArrayList<InstructionHandle> earliestLoads = new ArrayList<InstructionHandle>();
   ConstantPoolGen constantPoolGen = spawnableMethod.getConstantPool();

   try {
   d.log(1, "trying to get the earliest load associated with spawnable calls in basic block %d\n", basicBlock.getIndex());
   InstructionHandle earliestLoad =  getEarliestLoadInstruction(basicBlock.getInstructions(), 
   spawnableCalls, constantPoolGen);
   earliestLoads.add(earliestLoad);
   d.log(1, "succeeded to get the earliest load\n");

   removeSpawnableCallsBefore(earliestLoad, spawnableCalls, basicBlock);
   }
   catch (ResultNotLoadedException e) {
   InstructionHandle lastInstruction = basicBlock.getEnd().getInstruction();
   d.log(1, "result is not loaded in this basic block, defaulting to last instruction of the basic block\n");
   earliestLoads.add(lastInstruction);
   return earliestLoads;
   }


   while (spawnableCalls.size() > 0) {
   d.log(1, "there are spawnable calls after the earliest load, trying to get the earliest load for these spawnable calls\n");
   try {
   InstructionHandle earliestLoad =  getEarliestLoadInstruction(basicBlock.getInstructions(), 
   spawnableCalls, constantPoolGen);
   earliestLoads.add(earliestLoad);
   removeSpawnableCallsBefore(earliestLoad, spawnableCalls, basicBlock);
   }
   catch (ResultNotLoadedException e) {
   d.log(1, "there are no loads associated with these last spawnable calls\n");
   spawnableCalls.removeAll(spawnableCalls);
   }
   }

   return earliestLoads;
   }
   */






/*
   private class NotFoundException extends Exception {}


/* FOR OTHER CLASSES */

/* what instruction consumes what ih produces on the stack */
/*
   private InstructionHandle findInstructionConsumer(InstructionHandle ih, ConstantPoolGen constantPoolGen) throws NotFoundException {
   int stackConsumption = 0;
   int targetBalance = ih.getInstruction().produceStack(constantPoolGen);
   int lastProducedOnStack = 0;
   InstructionHandle current = ih;
   do {
   current = current.getNext();
   if (current.getInstruction() instanceof BranchInstruction) {
   throw new NotFoundException();
   }
   Instruction currentInstruction = current.getInstruction();
   lastProducedOnStack = currentInstruction.produceStack(constantPoolGen);
   stackConsumption-=lastProducedOnStack;
   stackConsumption+=currentInstruction.consumeStack(constantPoolGen);
   }
// ignoring the fact that the current instruction might also produce
// something
while (stackConsumption + lastProducedOnStack < targetBalance);

return current;
   }
   */





/* FOR OTHER CLASSES */

/* what instruction consumes what ih produces on the stack */
/*
   private InstructionHandle findInstructionConsumer(InstructionHandle ih, ConstantPoolGen constantPoolGen) {
   int stackConsumption = 0;
   int targetBalance = ih.getInstruction().produceStack(constantPoolGen);
   System.out.printf("targetBalance: %d\n", targetBalance);
   int lastProducedOnStack = 0;
   InstructionHandle current = ih;
   do {
   current = current.getNext();
   System.out.printf("\ncurrent instruction: %s\n", current);
   System.out.printf("stackConsumption: %d\n", stackConsumption);
   System.out.println("OK, what is consumed...");
   Instruction currentInstruction = current.getInstruction();
   lastProducedOnStack = currentInstruction.produceStack(constantPoolGen);
   System.out.printf("lastProducedOnStack: %d\n", lastProducedOnStack);
   stackConsumption-=lastProducedOnStack;
   System.out.printf("stackConsumption: %d\n", stackConsumption);
   stackConsumption+=currentInstruction.consumeStack(constantPoolGen);
   System.out.printf("stackConsumption: %d\n", stackConsumption);
   System.out.printf("stackConsumption + lastProducedOnStack: %d\n", stackConsumption + lastProducedOnStack);
   }
// ignoring the fact that the current instruction might also produce
// something
while (stackConsumption + lastProducedOnStack != targetBalance);

return current;
   }
   */



/*
   private boolean isUsedForArrayStore(InstructionHandle loadInstruction, ConstantPoolGen constantPoolGen) {
   if (loadInstruction.getInstruction() instanceof ALOAD) {
   try {
   InstructionHandle loadInstructionConsumer = findInstructionConsumer(loadInstruction, constantPoolGen);
   Instruction consumer = loadInstructionConsumer.getInstruction();
   return consumer instanceof AASTORE || consumer instanceof BASTORE || 
   consumer instanceof CASTORE || consumer instanceof DASTORE ||
   consumer instanceof FASTORE || consumer instanceof IASTORE ||
   consumer instanceof LASTORE || consumer instanceof SASTORE;
   }
   catch (NotFoundException e) {
   return false;
   }
   }
   return false;
   }
   */






/* what instruction consumes what ih produces on the stack */
/*
   private InstructionHandle findInstructionConsumer(InstructionHandle ih, ConstantPoolGen constantPoolGen) {
   int stackConsumption = 0;
   int targetBalance = ih.getInstruction().produceStack(constantPoolGen);
   int lastProducedOnStack = 0;
   InstructionHandle current = ih;
   do {
   current = current.getNext();
   Instruction currentInstruction = current.getInstruction();
   lastProducedOnStack = currentInstruction.produceStack(constantPoolGen);
   stackConsumption-=lastProducedOnStack;
   stackConsumption+=currentInstruction.consumeStack(constantPoolGen);
   }
// ignoring the fact that the current instruction might also produce
// something
while (stackConsumption + lastProducedOnStack != targetBalance);

return current;
   }





   private boolean isUsedForArrayStore(InstructionHandle loadInstruction, ConstantPoolGen constantPoolGen) {
   if (loadInstruction.getInstruction() instanceof ALOAD) {
   InstructionHandle loadInstructionConsumer = findInstructionConsumer(loadInstruction, constantPoolGen);
   Instruction consumer = loadInstructionConsumer.getInstruction();
   return consumer instanceof AASTORE || consumer instanceof BASTORE || 
   consumer instanceof CASTORE || consumer instanceof DASTORE ||
   consumer instanceof FASTORE || consumer instanceof IASTORE ||
   consumer instanceof LASTORE || consumer instanceof SASTORE;
   }
   return false;
   }
   */











/*

   private BasicBlock proposeBasicBlock2(SpawnableMethod spawnableMethod) 
   throws SyncInsertionProposalFailure {

   ArrayList<SpawnableCall> spawnableCalls = 
   spawnableMethod.getSpawnableCalls();
   BasicBlockGraph basicBlockGraph = new BasicBlockGraph(spawnableMethod);

   Path[] spawnToLoadPaths = calculateSpawnToLoadPaths(basicBlockGraph, spawnableCalls);
   d.log(1, "the paths from spawn to load are:\n");
   d.log(2, "%s\n", toString(spawnToLoadPaths));

   Path[] latestCommonSubPaths = getLatestCommonSubPaths(spawnToLoadPaths);

   Path latestCommonSubPath = getLatestCommonSubPath(latestCommonSubPaths);
   d.log(1, "the final latest common subpath\n");
   d.log(2, "%s\n", latestCommonSubPath);


   try {
   BasicBlock lastBasicBlockNotPartOfLoop = 
   getLastBasicBlockNotPartOfLoop(basicBlockGraph, latestCommonSubPath);
   d.log(1, "the last basic block not part of a loop: %d\n", lastBasicBlockNotPartOfLoop.getIndex());
   return lastBasicBlockNotPartOfLoop;
   }
   catch (PathPartOfLoopException e) {
   BasicBlock lastBasicBlock = latestCommonSubPath.getLastBasicBlock(); 
   d.warning("taking last basic block %d, but it is part of a loop\n", lastBasicBlock.getIndex());
   return lastBasicBlock;
   }
   }
   */



/*
   if (lastBasicBlocksTheSame(spawnToLoadPaths)) {
   BasicBlock lastBasicBlock = spawnToLoadPaths[0].getLastBasicBlock();
   return instructionHandles;
   }
   else {
   System.out.println("NOT ACCOUNTED FOR YET");
   }

   return instructionHandles;
   }
   */








/*
   private InstructionHandle getPositionSpawnableCall(ArrayList<InstructionContext> instructions, SpawnableCall spawnableCall) throws 
   ResultNotLoadedException {
   */
/*
   for (int i = 0; i < instructions.size(); i++) {
   if (spawnableCall.getInvokeInstruction().equals(instructions.get(i).getInstruction())) {
   return i;
   }
   }
   */
/*
   throw new ResultNotLoadedException();
   }
   */


/*

   private InstructionHandle getStackConsumer(ArrayList<InstructionContext> instructions, SpawnableCall spawnableCall) throws 
   ResultNotLoadedException {
   */

/*
   int positionSpawnableCall = getPositionSpawnableCall(instructions, spawnableCall);
   int stackProduction = instructions.get(positionSpawnableCall);
   for (int i = positionSpawnableCall; i < instructions.size(); i++) {
   InstructionHandle ih = instructions.get(i).getInstruction();
   try {
   LoadInstruction loadInstruction = 
   (LoadInstruction) (ih.getInstruction());
   if (loadInstruction.getIndex() == spawnableCall.getResultIndex()) {
   return ih;
   }
   }
   catch (ClassCastException e) {
   }
   }
   */
/*
   throw new ResultNotLoadedException();
   }
   */


/*
   private boolean lastBasicBlocksTheSame(Path[] paths) {
   BasicBlock lastBasicBlock = paths[0].getLastBasicBlock();
   boolean lastBasicBlocksTheSame = true;
   for (int i = 0; i < paths.length; i++) {
   if (!lastBasicBlock.equals(paths[i].getLastBasicBlock())) {
   lastBasicBlocksTheSame = false;
   }
   }
   return lastBasicBlocksTheSame;
   }
   */





/*
   private ArrayList<StoreLoadPath> getStoreLoadPaths(ArrayList<Path> 
   endingPaths, SpawnableCall spawnableCall) {

   ArrayList<StoreLoadPath> storeLoadPaths = 
   new ArrayList<StoreLoadPath>();

   for (Path path : endingPaths) {
   StoreLoadPath storeLoadPath = null;
   try {
   storeLoadPath = new StoreLoadPath(
   spawnableCall.getInvokeInstruction(), path, spawnableCall.getResultIndex());
   }
   catch (NeverReadException e) {
   }
   if (storeLoadPath != null && !storeLoadPaths.contains(storeLoadPath)) {
   storeLoadPaths.add(storeLoadPath);
   }
   }

   return storeLoadPaths;
   }
   */



/*
   private Path calculateSpawnToLoadPath(BasicBlockGraph basicBlockGraph, SpawnableCall spawnableCall) {
   int indexBasicBlock = 
   basicBlockGraph.getIndexBasicBlock(spawnableCall.getInvokeInstruction());

   d.log(1, "the spawnable call: %s\n", spawnableCall);

   ArrayList<Path> endingPaths = 
   basicBlockGraph.getEndingPathsFrom(indexBasicBlock);
   d.log(2, "ending paths from %d:\n", indexBasicBlock);
   d.log(3, "%s\n", endingPaths);

   ArrayList<StoreLoadPath> storeLoadPaths = getStoreLoadPaths(endingPaths, spawnableCall);
   d.log(2, "paths from store to load:");
   d.log(3, "%s\n", storeLoadPaths);

   Path latestCommonSubPath;
   if (storeLoadPaths.size() == 0) {
   d.warning("The result of spawnable call %s is never read, %s\n", spawnableCall,
   "defaulting to the latest common subpath of all ending paths");
   Path[] endingPathsArray = new Path[endingPaths.size()];
   latestCommonSubPath = 
   getLatestCommonSubPath(endingPaths.toArray(endingPathsArray));
   }
   else {
   Path[] storeLoadPathsArray = new Path[storeLoadPaths.size()];
   latestCommonSubPath = 
   getLatestCommonSubPath(storeLoadPaths.toArray(storeLoadPathsArray));
   }

   d.log(2, "latest common subbpath:\n");
   d.log(3, "%s\n", latestCommonSubPath);

   return latestCommonSubPath;
   }
   */


/*
   private Path[] calculateSpawnToLoadPaths(BasicBlockGraph basicBlockGraph, ArrayList<SpawnableCall> spawnableCalls) {
   Path[] spawnToLoadPaths = new Path[spawnableCalls.size()];

   for (int i = 0; i < spawnableCalls.size(); i++) {
   spawnToLoadPaths[i] = calculateSpawnToLoadPath(basicBlockGraph, spawnableCalls.get(i));
   }

   return spawnToLoadPaths;
   }
   */


/*
   private ArrayList<BasicBlock> filter2(ArrayList<BasicBlock> basicBlocks, ArrayList<Path> storeLoadPaths) {
   ArrayList<BasicBlock> toRemove = new ArrayList<BasicBlock>();
   ArrayList<BasicBlock> filteredBasicBlocks = new ArrayList<BasicBlock>(basicBlocks);

   for (int i = 0; i < basicBlocks.size(); i++) {
   if (hasBasicBlockBefore(basicBlocks.get(i), storeLoadPaths.get(i), basicBlocks)) {
   toRemove.add(basicBlocks.get(i));
   }
   }
   filteredBasicBlocks.removeAll(toRemove);

   return filteredBasicBlocks;
   }
   */



