package bsh;

import java.io.*;
import java.util.*;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
	This class is an implementation of the ClassGenerator interface which
	contains generally bsh related code.  The actual bytecode generation is
	done by ClassGeneratorUtil.
	
	@author Pat Niemeyer (pat@pat.net)
*/
public class ClassGeneratorImpl extends ClassGenerator
{
	public Class generateClass( 
		String name, Modifiers modifiers, 
		Class [] interfaces, Class superClass, BSHBlock block, 
		boolean isInterface, CallStack callstack, Interpreter interpreter 
	)
		throws EvalError
	{
		// Delegate to the static method
		return generateClassImpl( name, modifiers, interfaces, superClass,
			block, isInterface, callstack, interpreter );
	}

	public Object invokeSuperclassMethod(
		BshClassManager bcm, Object instance, String methodName, Object [] args
	)
        throws UtilEvalError, ReflectError, InvocationTargetException
	{
		// Delegate to the static method
		return invokeSuperclassMethodImpl( bcm, instance, methodName, args );
	}

	/**
		Change the parent of the class instance namespace.
		This is currently used for inner class support.
		Note: This method will likely be removed in the future.
	*/
	// This could be static
	public void setInstanceNameSpaceParent( 
		Object instance, String className, NameSpace parent )
	{
		This ithis = 
			ClassGeneratorUtil.getClassInstanceThis( instance, className );
		ithis.getNameSpace().setParent( parent );
	}

	/**
		If necessary, parse the BSHBlock for for the class definition and
		generate the class using ClassGeneratorUtil.
		This method also initializes the static block namespace and sets it
		in the class.
	*/
	public static Class generateClassImpl( 
		String name, Modifiers modifiers, 
		Class [] interfaces, Class superClass, BSHBlock block, 
		boolean isInterface, CallStack callstack, Interpreter interpreter 
	)
		throws EvalError
	{
		// Scripting classes currently requires accessibility
		// This can be eliminated with a bit more work.
		try {
			Capabilities.setAccessibility( true );
		} catch ( Capabilities.Unavailable e )
		{
			throw new EvalError(
				"Defining classes currently requires reflective Accessibility.",
				block, callstack );
		}

		NameSpace enclosingNameSpace = callstack.top();
		String packageName = enclosingNameSpace.getPackage();
		String className =  enclosingNameSpace.isClass ?
			( enclosingNameSpace.getName()+"$"+name ) : name;
		String fqClassName =
			packageName == null ? className : packageName + "." + className;
		String bshStaticFieldName = ClassGeneratorUtil.BSHSTATIC+className;

		BshClassManager bcm = interpreter.getClassManager();
		// Race condition here...
		bcm.definingClass( fqClassName );

		// Create the class static namespace
		NameSpace classStaticNameSpace =
			new NameSpace( enclosingNameSpace, className);
		classStaticNameSpace.isClass = true;

		callstack.push( classStaticNameSpace );

		// Evaluate any inner class class definitions in the block
		// effectively recursively call this method for contained classes first
		block.evalBlock(
			callstack, interpreter, true/*override*/,
			ClassNodeFilter.CLASSCLASSES );

		// Generate the type for our class
		Variable [] variables =
			getDeclaredVariables( block, callstack, interpreter, packageName );
		DelayedEvalBshMethod [] methods =
			getDeclaredMethods( block, callstack, interpreter, packageName );

		// Check for existing class (saved class file)
		Class genClass =
			getExistingGeneratedClass( bcm, fqClassName, bshStaticFieldName );

		// If the class doesn't exist or isn't a bsh generated class
		// then generate it
		if ( genClass == null )
			genClass = defineClass( fqClassName, modifiers, className,
				packageName, superClass, interfaces, variables, methods,
				classStaticNameSpace, isInterface, bcm );
		else
			Interpreter.debug("Found existing generated class: "+fqClassName );

		// import the unq name into parent
		enclosingNameSpace.importClass( fqClassName.replace('$','.') );

		try {
			classStaticNameSpace.setLocalVariable(
				ClassGeneratorUtil.BSHINIT, block, false/*strictJava*/ );
		} catch ( UtilEvalError e ) {
			throw new InterpreterError("unable to init static: "+e );
		}

		// Give the static space its class static import
		// important to do this after all classes are defined
		classStaticNameSpace.setClassStatic( genClass );

		// evaluate the static portion of the block in the static space
		block.evalBlock(
			callstack, interpreter, true/*override*/,
			ClassNodeFilter.CLASSSTATIC );

		callstack.pop();

		if ( !genClass.isInterface() )
			installStaticBlock( genClass, bshStaticFieldName,
				classStaticNameSpace, interpreter );

		bcm.doneDefiningClass( fqClassName );
		return genClass;
	}

	private static Class defineClass(
		String fqClassName, Modifiers modifiers, String className,
		String packageName, Class superClass, Class[] interfaces,
		Variable[] variables, DelayedEvalBshMethod[] methods,
		NameSpace classStaticNameSpace, boolean isInterface,
		BshClassManager bcm )
	{
		Interpreter.debug("generating class: "+fqClassName );
		ClassGeneratorUtil classGenerator = new ClassGeneratorUtil(
			modifiers, className, packageName, superClass, interfaces,
			variables, methods, classStaticNameSpace, isInterface );
		byte [] code = classGenerator.generateClass();

		// if debug, write out the class file to debugClasses directory
		debugClasses( className, code );

		// Define the new class in the classloader
		return bcm.defineClass( fqClassName, code );
	}

	private static void installStaticBlock(
		Class genClass, String bshStaticFieldName,
		NameSpace classStaticNameSpace, Interpreter interpreter )
	{
		// Set the static bsh This callback
		try {
			LHS lhs = Reflect.getLHSStaticField( genClass, bshStaticFieldName );
			lhs.assign(
				classStaticNameSpace.getThis( interpreter ), false/*strict*/ );
		} catch ( Exception e ) {
			throw new InterpreterError("Error in class gen setup: "+e );
		}
	}

	private static void debugClasses( String className, byte[] code )
	{
		String dir = System.getProperty("debugClasses");
		if ( dir != null )
		try {
			FileOutputStream out=
				new FileOutputStream( dir+"/"+className+".class" );
			out.write(code);
			out.close();
		} catch ( IOException e ) { }
	}

	static Variable [] getDeclaredVariables( 
		BSHBlock body, CallStack callstack, Interpreter interpreter, 
		String defaultPackage 
	) 
	{
		List vars = new ArrayList();
		for( int child=0; child<body.jjtGetNumChildren(); child++ )
		{
			SimpleNode node = (SimpleNode)body.jjtGetChild(child);
			if ( node instanceof BSHTypedVariableDeclaration )
			{
				BSHTypedVariableDeclaration tvd = 
					(BSHTypedVariableDeclaration)node;
				Modifiers modifiers = tvd.modifiers;

				String type = tvd.getTypeDescriptor( 
					callstack, interpreter, defaultPackage );

				BSHVariableDeclarator [] vardec = tvd.getDeclarators();
				for( int i = 0; i< vardec.length; i++)
				{
					String name = vardec[i].name;
					try {
						Variable var = new Variable( 
							name, type, null/*value*/, modifiers );
						vars.add( var );
					} catch ( UtilEvalError e ) {
						// value error shouldn't happen
					}
				}
			}
		}

		return (Variable [])vars.toArray( new Variable[0] );
	}

	static DelayedEvalBshMethod [] getDeclaredMethods( 
		BSHBlock body, CallStack callstack, Interpreter interpreter,
		String defaultPackage 
	)
	{
		List methods = new ArrayList();
		for( int child=0; child<body.jjtGetNumChildren(); child++ )
		{
			SimpleNode node = (SimpleNode)body.jjtGetChild(child);
			if ( node instanceof BSHMethodDeclaration )
			{
				BSHMethodDeclaration md = (BSHMethodDeclaration)node;
				md.insureNodesParsed();
				Modifiers modifiers = md.modifiers;
				String name = md.name;
				String returnType = md.getReturnTypeDescriptor( 
					callstack, interpreter, defaultPackage );
				BSHReturnType returnTypeNode = md.getReturnTypeNode();
				BSHFormalParameters paramTypesNode = md.paramsNode;
				String [] paramTypes = paramTypesNode.getTypeDescriptors( 
					callstack, interpreter, defaultPackage );

				DelayedEvalBshMethod bm = new DelayedEvalBshMethod( 
					name, 
					returnType, returnTypeNode,
					md.paramsNode.getParamNames(), 
					paramTypes, paramTypesNode,
					md.blockNode, null/*declaringNameSpace*/,
					modifiers, callstack, interpreter 
				);

				methods.add( bm );
			}
		}

		return (DelayedEvalBshMethod [])methods.toArray( 
			new DelayedEvalBshMethod[0] );
	}

	/**
		A node filter that filters nodes for either a class body static 
		initializer or instance initializer.  In the static case only static 
		members are passed, etc.  
	*/
	static class ClassNodeFilter implements BSHBlock.NodeFilter
	{
		public static final int STATIC=0, INSTANCE=1, CLASSES=2;

		public static ClassNodeFilter CLASSSTATIC = 
			new ClassNodeFilter( STATIC );
		public static ClassNodeFilter CLASSINSTANCE = 
			new ClassNodeFilter( INSTANCE );
		public static ClassNodeFilter CLASSCLASSES = 
			new ClassNodeFilter( CLASSES );

		int context;

		private ClassNodeFilter( int context ) { this.context = context; }

		public boolean isVisible( SimpleNode node ) 
		{
			if ( context == CLASSES )
				return node instanceof BSHClassDeclaration;

			// Only show class decs in CLASSES
			if ( node instanceof BSHClassDeclaration )
				return false;

			if ( context == STATIC )
				return isStatic( node );

			if ( context == INSTANCE )
				return !isStatic( node );

			// ALL
			return true;
		}

		boolean isStatic( SimpleNode node ) 
		{
			if ( node instanceof BSHTypedVariableDeclaration )
				return ((BSHTypedVariableDeclaration)node).modifiers != null
					&& ((BSHTypedVariableDeclaration)node).modifiers
						.hasModifier("static");

			if ( node instanceof BSHMethodDeclaration )
				return ((BSHMethodDeclaration)node).modifiers != null
					&& ((BSHMethodDeclaration)node).modifiers
						.hasModifier("static");

			// need to add static block here
			if ( node instanceof BSHBlock)
				return false;

			return false;
		}
	}

	public static Object invokeSuperclassMethodImpl(
		BshClassManager bcm, Object instance, String methodName, Object [] args
	)
        throws UtilEvalError, ReflectError, InvocationTargetException
	{
		String superName = ClassGeneratorUtil.BSHSUPER+methodName;
		
		// look for the specially named super delegate method
		Class clas = instance.getClass();
		Method superMethod = Reflect.resolveJavaMethod(
			bcm, clas, superName, Types.getTypes(args), false/*onlyStatic*/ );
		if ( superMethod != null )
			return Reflect.invokeMethod(
				superMethod, instance, args );

		// No super method, try to invoke regular method
		// could be a superfluous "super." which is legal.
		Class superClass = clas.getSuperclass();
		superMethod = Reflect.resolveExpectedJavaMethod(
			bcm, superClass, instance, methodName, args, 
			false/*onlyStatic*/ );
		return Reflect.invokeMethod( superMethod, instance, args );
	}

	/*
		Check for an existing generated bsh class and return it or null.
		The class must be one we generated.
	 */
	private static Class getExistingGeneratedClass(
		BshClassManager bcm, String fqClassName, String bshStaticFieldName )
	{
		Class genClass = bcm.classForName( fqClassName );

		boolean isGenClass = false;
		if ( genClass != null )
			try {
				isGenClass = Reflect.resolveJavaField(
					genClass, bshStaticFieldName, true/*staticOnly*/ ) != null;
			} catch ( Exception e ) { /*ignore*/ } // TODO: make more specific

		return isGenClass ? genClass : null;
	}
}
