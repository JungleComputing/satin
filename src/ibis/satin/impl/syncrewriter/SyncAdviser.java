package ibis.satin.impl.syncrewriter;

import java.util.ArrayList;
import java.util.Iterator;

import org.apache.bcel.classfile.JavaClass;

import ibis.satin.impl.syncrewriter.util.Debug;

public class SyncAdviser extends SyncRewriter {

    void advise(JavaClass javaClass, SpawnSignature[] spawnSignatures) throws NoSpawningClassException, AssumptionFailure {
        String className = javaClass.getClassName();
        SpawningClass spawnableClass = new SpawningClass(javaClass, spawnSignatures, new Debug(d.turnedOn(), 2));
        d.log(0, "%s is a spawning class\n", className);
        d.log(1, "it contains calls with spawn signatures:\n");
        print(spawnableClass.getSpawnSignatures(), 2);
        spawnableClass.advise(analyzer);
    }

    void advise(String className, SpawnSignature[] spawnSignatures) throws NoSpawningClassException, AssumptionFailure {

        JavaClass javaClass = getClassFromName(className);
        SpawningClass spawnableClass = new SpawningClass(javaClass, spawnSignatures, new Debug(d.turnedOn(), 2));
        d.log(0, "%s is a spawning class\n", className);
        d.log(1, "it contains calls with spawn signatures:\n");
        print(spawnableClass.getSpawnSignatures(), 2);
        spawnableClass.advise(analyzer);
    }

    @Override
    void start(String[] argv) {

        processArguments(argv);
        if (analyzer == null) {
            setAnalyzer("ControlFlow");
        }

        d.log(0, "analyzing for following spawnsignatures:\n");
        SpawnSignature[] spawnSignatures = getSpawnSignatures(classNames);
        print(spawnSignatures, 1);

        for (String classFileName : classNames) {
            String className = getClassName(classFileName);
            try {
                advise(className, spawnSignatures);
            } catch (NoSpawningClassException e) {
                d.warning("%s is not a spawning class", className);
            } catch (AssumptionFailure e) {
                d.error("%s has spawns that don't return a value or throw an inlet." + " The syncadviser cannot handle this.", className);
                System.exit(1);
            }
        }
    }

    @Override
    void printUsage() {
        System.out.println("Usage:");
        System.out.println("sync-advise [options] classname...");
        System.out.println("  example sync-advise mypackage.MyClass");
        System.out.println("Options:");
        System.out.printf("  -debug               %s\n", "print debug information");
        System.out.printf("  -help                %s\n", "this information");
        System.out.printf("  -analyzerinfo        %s\n", "show info about all analyzers");
        System.out.printf("  -analyzer analyzer   %s\n", "load analyzer analyzer");
    }

    @Override
    public String getUsageString() {
        return "[-syncadvise <classlist>]";
    }

    @Override
    public void process(Iterator<?> classes) {
        if (classNames.size() == 0) {
            System.exit(0);
        }

        if (analyzer == null) {
            setAnalyzer("ControlFlow");
        }

        d.log(0, "advising for following spawnsignatures:\n");
        SpawnSignature[] spawnSignatures = getSpawnSignatures(classNames);
        print(spawnSignatures, 1);

        while (classes.hasNext()) {
            JavaClass clazz = (JavaClass) classes.next();
            if (classNames.contains(clazz.getClassName())) {
                try {
                    advise(clazz, spawnSignatures);
                } catch (NoSpawningClassException e) {
                    d.log(0, "%s is not a spawning class\n", clazz.getClassName());
                } catch (AssumptionFailure e) {
                    d.error("%s has spawns that don't return a value or throw an inlet." + " The syncadviser cannot handle this.",
                            clazz.getClassName());
                    System.exit(1);
                }
            }
        }
        System.exit(0);
    }

    @Override
    @SuppressWarnings("unused")
    public boolean processArgs(ArrayList<String> args) {
        boolean retval = false;
        for (int i = 0; i < args.size(); i++) {
            String arg = args.get(i);
            if (false) {
                // nothing
            } else if (arg.equals("-syncadvise-debug")) {
                d.turnOn();
                args.remove(i--);
            } else if (arg.equals("-syncadvise-analyzer")) {
                args.remove(i);
                if (i >= args.size()) {
                    throw new IllegalArgumentException("-syncadvise-analyzer needs analyzer");
                }
                setAnalyzer(args.get(i));
                args.remove(i--);
            } else if (arg.equals("-syncadvise")) {
                args.remove(i);
                retval = true;
                if (i >= args.size()) {
                    throw new IllegalArgumentException("-syncadvise needs classlist");
                }
                addToClassList(args.get(i));
                args.remove(i--);
            }
        }
        return retval;
    }

    @Override
    public String rewriterImpl() {
        return "BCEL";
    }

    /**
     * @param args
     */
    public static void main(String[] args) {

        new SyncAdviser().start(args);
    }
}
