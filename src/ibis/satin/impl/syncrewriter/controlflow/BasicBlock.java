package ibis.satin.impl.syncrewriter.controlflow;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

import org.apache.bcel.generic.InstructionHandle;
import org.apache.bcel.verifier.structurals.InstructionContext;

import ibis.satin.impl.syncrewriter.bcel.MethodGen;

/**
 * A BasicBlock is a sequence of instructions with only one entry point and only
 * one exit point.
 *
 * Instructions don't branch to other instructions and instructions are not
 * targeted by other basic blocks.
 */
public class BasicBlock {

    protected MethodGen methodGen;

    private InstructionContext start;
    protected ArrayList<InstructionContext> instructions;
    private InstructionContext end;

    private int id;
    private BasicBlock[] targets;
    private HashSet<BasicBlock> predecessors = new HashSet<>();
    private int level;

    /* public methods */

    /**
     * Tests whether this basic block contains {@link InstructionHandle} ih
     *
     * @param ih the instruction handle that will be tested
     * @return true if this basic block contains ih; false otherwise
     */
    public boolean contains(InstructionHandle ih) {
        for (InstructionContext instructionContext : instructions) {
            if (instructionContext.getInstruction().equals(ih)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns a string representation of the basic block.
     */
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("BEGIN BASIC BLOCK: %d\n", id));
        sb.append(String.format("LEVEL: %d\n", level));
        sb.append(getTargetString());
        for (InstructionContext context : instructions) {
            sb.append(context.getInstruction().getPosition());
            sb.append(":\t");
            sb.append(context.getInstruction().getInstruction().toString(methodGen.getConstantPool().getConstantPool()));
            sb.append('\n');
        }
        sb.append(String.format("END BASIC BLOCK: %d\n", id));

        return sb.toString();
    }

    public MethodGen getMethodGen() {
        return methodGen;
    }

    /**
     * Returns the id of the basic block.
     *
     * Note: This is the index of the basic block in the method. So, basic block
     * with id + 1 is the following basic block in the instruction list of the
     * method. It is not necessarily targeted by the previous one.
     *
     * @return the id, which is the index of the basic block in the method.
     */
    public int getId() {
        return id;
    }

    /**
     * Returns the last {@link InstructionContext} of the basic block.
     *
     * @return the last instruction context of the basic block.
     */
    public InstructionContext getEnd() {
        return end;
    }

    /**
     * Returns an {@link ArrayList} of the instructions of the basic block.
     *
     * The instruction are in execution order.
     *
     * @return The instructions of the basic block in execution order.
     */
    public ArrayList<InstructionContext> getInstructions() {
        return instructions;
    }

    /* protected methods */

    protected BasicBlock(BasicBlock basicBlock) {

        this.methodGen = basicBlock.methodGen;

        this.start = basicBlock.start;
        this.instructions = basicBlock.instructions;
        this.end = basicBlock.end;

        this.id = basicBlock.id;
        this.targets = basicBlock.targets;
        this.level = basicBlock.level;
        this.predecessors = basicBlock.predecessors;
    }

    /* package methods */

    BasicBlock(InstructionContext start, ArrayList<InstructionContext> instructions, InstructionContext end, int id, MethodGen methodGen) {
        this.start = start;
        this.instructions = instructions;
        this.end = end;
        this.id = id;

        this.targets = new BasicBlock[end.getSuccessors().length];
        this.level = -1;

        this.methodGen = methodGen;
    }

    int getLevel() {
        return level;
    }

    InstructionContext getStart() {
        return start;
    }

    void setTarget(int id, BasicBlock target) {
        targets[id] = target;
        target.predecessors.add(this);
    }

    public Set<BasicBlock> getAllPredecessors() {
        HashSet<BasicBlock> result = new HashSet<>(predecessors);
        int size = 0;
        do {
            size = result.size();
            BasicBlock[] blocks = result.toArray(new BasicBlock[result.size()]);
            for (BasicBlock b : blocks) {
                result.addAll(b.predecessors);
            }
        } while (size < result.size());
        return result;
    }

    BasicBlock getTarget(int id) {
        return targets[id];
    }

    boolean levelIsSet() {
        return level != -1;
    }

    boolean allLevelsTargetsSet() {
        for (BasicBlock target : targets) {
            if (!target.levelIsSet()) {
                return false;
            }
        }
        return true;
    }

    void setLevel(int level) {
        this.level = level;
    }

    void setLevelTargets(int level) {
        for (BasicBlock target : targets) {
            if (!target.levelIsSet()) {
                target.setLevel(level);
            }
        }
    }

    void setLevelTarget(int id, int level) {
        if (!targets[id].levelIsSet()) {
            targets[id].setLevel(level);
        }
    }

    int getNrOfTargets() {
        return targets.length;
    }

    int getNrOfPredecessors() {
        return predecessors.size();
    }

    boolean isStarting() {
        return predecessors.size() == 0;
    }

    boolean isEnding() {
        return targets.length == 0;
    }

    boolean isPredecessorOf(BasicBlock basicBlock) {
        return predecessors.contains(basicBlock);
    }

    boolean targets(BasicBlock basicBlock) {
        for (BasicBlock target : targets) {
            if (target == basicBlock) {
                return true;
            }
        }
        return false;
    }

    String toStringNoInstructions() {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("BEGIN BASICBLOCK: %d\n", id));
        sb.append(String.format("LEVEL: %d\n", level));
        sb.append(getTargetString());
        sb.append(String.format("END BASICBLOCK %d\n", id));

        return sb.toString();
    }

    /* private methods */

    private StringBuilder getTargetString() {
        StringBuilder sb = new StringBuilder();
        sb.append("TARGETS: ");
        if (targets.length == 0) {
            sb.append("ending Basic block\n");
        } else {
            sb.append(String.format("Basic block %d\n", targets[0].getId()));
            for (int i = 1; i < targets.length; i++) {
                sb.append(String.format("         Basic block %d\n", targets[i].getId()));
            }
        }
        return sb;
    }
}
