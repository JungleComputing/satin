/* $Id$ */

package ibis.satin.impl.rewriter;

import java.util.Vector;

import org.apache.bcel.Repository;
import org.apache.bcel.classfile.JavaClass;
import org.apache.bcel.classfile.Method;

public class BT_Analyzer {
    boolean verbose;

    public JavaClass specialInterface;

    public JavaClass subject;

    public String classname;

    public String packagename;

    public Vector<JavaClass> specialInterfaces;

    public Vector<Method> specialMethods;

    public Vector<Method> subjectSpecialMethods;

    public BT_Analyzer(JavaClass subject, JavaClass specialInterface, boolean verbose) {
        this.subject = subject;
        this.specialInterface = specialInterface;
        this.verbose = verbose;

        if (verbose) {
            System.out.println("BT_Analyzer looking for " + specialInterface.getClassName() + " in " + subject.getClassName());
        }
    }

    private boolean compareMethods(Method m1, Method m2) {
        return m1.getSignature().equals(m2.getSignature()) && m1.getName().equals(m2.getName());
    }

    public boolean isSpecial(Method m1) {
        if (specialMethods == null) {
            return false;
        }

        for (Method m2 : specialMethods) {
            if (compareMethods(m1, m2)) {
                return true;
            }
        }

        return false;
    }

    public boolean hasSpecialMethods() {
        if (specialMethods == null) {
            return false;
        }
        return specialMethods.size() > 0;
    }

    private void addSpecialMethod(Vector<Method> sm, Method m) {
        for (Method element : sm) {
            if (compareMethods(m, element)) {
                // it is already in the vector
                return;
            }
        }

        sm.addElement(m);
    }

    private void findSpecialMethods(JavaClass si, Vector<Method> sm) {
        Method[] methods = si.getMethods();

        for (Method method : methods) {
            if (!method.getName().equals("<clinit>")) {
                addSpecialMethod(sm, method);
            }
        }
    }

    Vector<Method> findSpecialMethods(Vector<JavaClass> si) {
        Vector<Method> mi = new Vector<>();

        for (JavaClass element : si) {
            findSpecialMethods(element, mi);
        }

        return mi;
    }

    private void findSubjectSpecialMethod(Method[] sub, Method special, Vector<Method> dest) {

        for (Method temp : sub) {

            if (compareMethods(special, temp)) {
                // System.out.println(temp.fullName() + " equals "
                // + special.fullName());
                dest.add(temp);
            }
        }
    }

    Vector<Method> findSubjectSpecialMethods() {
        Vector<Method> temp = new Vector<>();
        Method v[] = subject.getMethods();

        for (Method specialMethod : specialMethods) {
            findSubjectSpecialMethod(v, specialMethod, temp);
        }

        return temp;
    }

    boolean findSpecialInterfaces(String inter, Vector<JavaClass> si) {
        boolean result = false;

        JavaClass interf = null;

        try {
            interf = Repository.lookupClass(inter);
        } catch (ClassNotFoundException e) {
            System.err.println("interface " + inter + " not found.");
            throw new Error(e);
        }

        if (inter.equals(specialInterface.getClassName())) {
            result = true;
        } else {
            String[] interfaces = interf.getInterfaceNames();

            for (String element : interfaces) {
                result |= findSpecialInterfaces(element, si);
            }
        }

        if (result) {
            if (!si.contains(interf)) {
                si.addElement(interf);
            }
        }

        return result;
    }

    Vector<JavaClass> findSpecialInterfaces() {
        Vector<JavaClass> si = new Vector<>();

        if (!subject.isClass()) {
            findSpecialInterfaces(subject.getClassName(), si);
        } else {
            String[] interfaces = subject.getInterfaceNames();

            for (String element : interfaces) {
                if (verbose) {
                    System.out.println(subject.getClassName() + " implements " + element);
                }
                findSpecialInterfaces(element, si);
            }
        }

        return si;
    }

    void findSpecialInterfaces2(JavaClass inter, Vector<JavaClass> si) {
        // boolean result = false;

        JavaClass[] interfaces;

        try {
            interfaces = inter.getAllInterfaces();
        } catch (ClassNotFoundException e) {
            System.err.println("Got exception " + e);
            e.printStackTrace(System.err);
            throw new Error(e);
        }

        for (JavaClass element : interfaces) {
            if (!si.contains(element)) {
                si.addElement(element);
            }
        }

        if (!si.contains(inter)) {
            si.addElement(inter);
        }
    }

    Vector<JavaClass> findSpecialInterfaces2() {
        Vector<JavaClass> si = new Vector<>();

        try {
            if (!subject.isClass()) {
                if (subject.implementationOf(specialInterface)) {
                    findSpecialInterfaces2(subject, si);
                }
            } else {
                JavaClass[] interfaces;
                interfaces = subject.getInterfaces();

                for (JavaClass element : interfaces) {
                    if (element.implementationOf(specialInterface)) {
                        findSpecialInterfaces2(element, si);
                    }
                }
            }
        } catch (ClassNotFoundException e) {
            System.err.println("Got exception " + e);
            e.printStackTrace(System.err);
            throw new Error(e);
        }

        return si;
    }

    public void start(boolean rmi_like) {

        String temp = subject.getClassName();
        packagename = subject.getPackageName();
        classname = temp.substring(temp.lastIndexOf('.') + 1);

        if (rmi_like) {
            specialInterfaces = findSpecialInterfaces2();
        } else {
            specialInterfaces = findSpecialInterfaces();
        }

        if (specialInterfaces.size() == 0) {
            if (verbose) {
                System.out.println("Class " + subject.getClassName() + " does not implement " + specialInterface.getClassName());
            }

            return;
        }

        if (verbose) {
            System.out.println(specialInterfaces.size() + " special interfaces found in " + subject.getClassName());

            for (int i = 0; i < specialInterfaces.size(); i++) {
                System.out.println("\t" + specialInterfaces.elementAt(i).getClassName());
            }
        }

        specialMethods = findSpecialMethods(specialInterfaces);

        if (verbose) {
            System.out.println(specialMethods.size() + " special methods found in " + subject.getClassName());

            for (Method specialMethod : specialMethods) {
                System.out.println("\t" + specialMethod.toString());
            }
        }

        subjectSpecialMethods = findSubjectSpecialMethods();
    }
}
