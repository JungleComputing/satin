package ibis.satin.impl.syncrewriter;

import java.lang.reflect.InvocationTargetException;

class AnalyzerFactory {

    static Analyzer createAnalyzer(String analyzerName)
	    throws ClassNotFoundException, IllegalAccessException,
	    InstantiationException, IllegalArgumentException, InvocationTargetException, NoSuchMethodException, SecurityException {

	ClassLoader classLoader = AnalyzerFactory.class.getClassLoader();
	Class<?> analyzerClass = classLoader
		.loadClass("ibis.satin.impl.syncrewriter.analyzer."
			+ analyzerName);

	return (Analyzer) analyzerClass.getDeclaredConstructor().newInstance();
    }
}
