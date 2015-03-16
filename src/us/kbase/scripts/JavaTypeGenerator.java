package us.kbase.scripts;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringTokenizer;
import java.util.TreeSet;

import org.kohsuke.args4j.Argument;
import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.Option;

import us.kbase.kidl.KbBasicType;
import us.kbase.kidl.KbFuncdef;
import us.kbase.kidl.KbList;
import us.kbase.kidl.KbMapping;
import us.kbase.kidl.KbModule;
import us.kbase.kidl.KbModuleComp;
import us.kbase.kidl.KbParameter;
import us.kbase.kidl.KbScalar;
import us.kbase.kidl.KbService;
import us.kbase.kidl.KbStruct;
import us.kbase.kidl.KbStructItem;
import us.kbase.kidl.KbTuple;
import us.kbase.kidl.KbType;
import us.kbase.kidl.KbTypedef;
import us.kbase.kidl.KbUnspecifiedObject;
import us.kbase.kidl.KidlParseException;
import us.kbase.kidl.KidlParser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.googlecode.jsonschema2pojo.DefaultGenerationConfig;
import com.googlecode.jsonschema2pojo.Jackson2Annotator;
import com.googlecode.jsonschema2pojo.Schema;
import com.googlecode.jsonschema2pojo.SchemaGenerator;
import com.googlecode.jsonschema2pojo.SchemaMapper;
import com.googlecode.jsonschema2pojo.SchemaStore;
import com.googlecode.jsonschema2pojo.rules.Rule;
import com.googlecode.jsonschema2pojo.rules.RuleFactory;
import com.sun.codemodel.JBlock;
import com.sun.codemodel.JCodeModel;
import com.sun.codemodel.JDefinedClass;
import com.sun.codemodel.JExpr;
import com.sun.codemodel.JExpression;
import com.sun.codemodel.JFieldVar;
import com.sun.codemodel.JMethod;
import com.sun.codemodel.JMod;
import com.sun.codemodel.JOp;
import com.sun.codemodel.JPackage;
import com.sun.codemodel.JType;

public class JavaTypeGenerator {
	private static final char[] propWordDelim = {'_', '-'};

	private static final String defaultParentPackage = "us.kbase";
	private static final String utilPackage = defaultParentPackage + ".common.service";
	
	public static void main(String[] args) throws Exception {
		Args parsedArgs = new Args();
		CmdLineParser parser = new CmdLineParser(parsedArgs);
		parser.setUsageWidth(85);
		if (args.length == 0 || (args.length == 1 && (args[0].equals("-h") || args[0].equals("--help")))) {
            parser.parseArgument("no.spec");
            showUsage(parser, null, System.out);
            return;
		}
		try {
            parser.parseArgument(args);
        } catch( CmdLineException e ) {
        	String message = e.getMessage();
            showUsage(parser, message);
            return;
        }
		File inputFile = parsedArgs.specFile;
		File tempDir = parsedArgs.tempDir == null ? inputFile.getAbsoluteFile().getParentFile() : new File(parsedArgs.tempDir);
		boolean deleteTempDir = false;
		if (!tempDir.exists()) {
			tempDir.mkdir();
			deleteTempDir = true;
		}
		File srcOutDir = null;
		String packageParent = parsedArgs.packageParent;
		File libDir = null;
		if (parsedArgs.outputDir == null) {
			if (parsedArgs.srcDir == null) {
	            showUsage(parser, "Either -o or -s parameter should be defined");
	            return;
			}
			srcOutDir = new File(parsedArgs.srcDir);
			libDir = parsedArgs.libDir == null ? null : new File(parsedArgs.libDir);
		} else {
			srcOutDir = new File(parsedArgs.outputDir, "src");
			libDir = new File(parsedArgs.outputDir, "lib");
		}
		boolean createServer = parsedArgs.createServerSide;
		URL url = null;
		if (parsedArgs.url != null) {
			try {
				url = new URL(parsedArgs.url);
			} catch (MalformedURLException mue) {
				String msg = "The provided url " + parsedArgs.url + " is invalid.";
				showUsage(parser, msg);
				return;
			}
		}
		processSpec(inputFile, tempDir, srcOutDir, packageParent, createServer, libDir, parsedArgs.gwtPackage, url);
		if (deleteTempDir)
			tempDir.delete();
	}

	private static void showUsage(CmdLineParser parser, String message) {
		showUsage(parser, message, System.err);
	}
	
	private static void showUsage(CmdLineParser parser, String message, PrintStream out) {
		if (message != null)
			out.println(message);
		out.println("Program generates java client and server classes for JSON RPC calls.");
		out.println("Usage: <program> [options...] <spec-file>");
		out.println("Usage: <program> {-h|--help}     - to see this help");
		parser.printUsage(out);
	}
	
	public static JavaData processSpec(File specFile, File tempDir, File srcOutDir, String packageParent, 
			boolean createServer, File libOutDir, String gwtPackage, URL url) throws Exception {		
		List<KbService> services = KidlParser.parseSpec(specFile, tempDir, null, null, true);
		return processSpec(services, tempDir, srcOutDir, packageParent, createServer, libOutDir, gwtPackage, url);
	}
	
	public static JavaData processSpec(List<KbService> services, File tempDir, File srcOutDir, String packageParent, 
			boolean createServer, File libOutDir, String gwtPackage, URL url) throws Exception {		
		return processParsingFile(services, new File(tempDir, "json-schemas"), 
				srcOutDir, packageParent, createServer, libOutDir, gwtPackage, url);
	}
		
	private static JavaData processParsingFile(List<KbService> services, File jsonSchemaOutDir, File srcOutDir, String packageParent, 
			boolean createServer, File libOutDir, String gwtPackage, URL url) throws Exception {		
		JavaData data = prepareDataStructures(services);
		outputData(data, jsonSchemaOutDir, srcOutDir, packageParent, createServer, libOutDir, gwtPackage, url);
		return data;
	}

	private static JavaData prepareDataStructures(List<KbService> services) {
		Set<JavaType> nonPrimitiveTypes = new TreeSet<JavaType>();
		JavaData data = new JavaData();
		for (KbService service: services) {
			for (KbModule module : service.getModules()) {
				List<JavaFunc> funcs = new ArrayList<JavaFunc>();
				Set<Integer> tupleTypes = data.getTupleTypes();
				for (KbModuleComp comp : module.getModuleComponents()) {
					if (comp instanceof KbFuncdef) {
						String moduleName = module.getModuleName();
						KbFuncdef func = (KbFuncdef)comp;
						String funcJavaName = TextUtils.inCamelCase(func.getName());
						List<JavaFuncParam> params = new ArrayList<JavaFuncParam>();
						for (KbParameter param : func.getParameters()) {
							JavaType type = findBasic(param.getType(), module.getModuleName(), nonPrimitiveTypes, tupleTypes);
							params.add(new JavaFuncParam(param, TextUtils.inCamelCase(param.getName()), type));
						}
						List<JavaFuncParam> returns = new ArrayList<JavaFuncParam>();
						for (KbParameter param : func.getReturnType()) {
							JavaType type = findBasic(param.getType(), module.getModuleName(), nonPrimitiveTypes, tupleTypes);
							returns.add(new JavaFuncParam(param, param.getName() == null ? null : TextUtils.inCamelCase(param.getName()), type));
						}
						JavaType retMultiType = null;
						if (returns.size() > 1) {
							List<KbType> subTypes = new ArrayList<KbType>();
							for (JavaFuncParam retPar : returns)
								subTypes.add(retPar.getOriginal().getType());
							KbTuple tuple = new KbTuple(subTypes);
							retMultiType = new JavaType(null, tuple, moduleName, new ArrayList<KbTypedef>());
							for (JavaFuncParam retPar : returns)
								retMultiType.addInternalType(retPar.getType());
							tupleTypes.add(returns.size());
						}
						funcs.add(new JavaFunc(moduleName, func, funcJavaName, params, returns, retMultiType));
					} else {
						findBasic((KbTypedef)comp, module.getModuleName(), nonPrimitiveTypes, tupleTypes);
					}
				}
				data.addModule(module, funcs);
			}
		}
		data.setTypes(nonPrimitiveTypes);
		return data;
	}

	private static void outputData(JavaData data, File jsonOutDir, File srcOutDir, String packageParent, 
			boolean createServers, File libOutDir, String gwtPackage, URL url) throws Exception {
		if (!srcOutDir.exists())
			srcOutDir.mkdirs();
		generatePojos(data, jsonOutDir, srcOutDir, packageParent);
		generateTupleClasses(data,srcOutDir, packageParent);
		generateClientClass(data, srcOutDir, packageParent, url);
		if (createServers)
			generateServerClass(data, srcOutDir, packageParent);
		checkLibs(libOutDir, createServers);
		if (gwtPackage != null) {
			GwtGenerator.generate(data, srcOutDir, gwtPackage);
		}
	}

	private static void generatePojos(JavaData data, File jsonOutDir,
			File srcOutDir, String packageParent) throws Exception {
		for (JavaType type : data.getTypes()) {
			Set<Integer> tupleTypes = data.getTupleTypes();
			File dir = new File(jsonOutDir, type.getModuleName());
			if (!dir.exists())
				dir.mkdirs();
			File jsonFile = new File(dir, type.getJavaClassName() + ".json"); 
			writeJsonSchema(jsonFile, packageParent, type, tupleTypes);
		}
		JCodeModel codeModel = new JCodeModel();
		DefaultGenerationConfig cfg = new DefaultGenerationConfig() {
			@Override
			public char[] getPropertyWordDelimiters() {
				return propWordDelim;
			}
			@Override
			public boolean isIncludeHashcodeAndEquals() {
				return false;
			}
			@Override
			public boolean isIncludeToString() {
				return false;
			}
			@Override
			public boolean isIncludeJsr303Annotations() {
				return false;
			}
			@Override
			public boolean isGenerateBuilders() {
				return true;
			}
			@Override
			public boolean isUseLongIntegers() {
				return true;
			}
		};
		SchemaStore ss = new SchemaStore();
		RuleFactory rf = new RuleFactory(cfg, new Jackson2Annotator(), ss) {
			@Override
			public Rule<JPackage, JType> getObjectRule() {
				return new JsonSchemaToPojoCustomObjectRule(this) {
					@Override
					public JType apply(String nodeName, JsonNode node,
							JPackage _package, Schema schema) {
						JType jclass = super.apply(nodeName, node, _package, schema);
						if (jclass instanceof JDefinedClass) {
							addToString((JDefinedClass)jclass);
						}
						return jclass;
					}
					
				    private void addToString(JDefinedClass jclass) {
				    	if (jclass.getMethod("toString", new JType[0]) != null)
				    		return;
				        JMethod toString = jclass.method(JMod.PUBLIC, String.class, "toString");
				        JBlock body = toString.body();
				        JExpression ret = JExpr.lit(jclass.name());
				        boolean firstField = true;
				        for (Map.Entry<String, JFieldVar> entry : jclass.fields().entrySet()) {
				        	ret = JOp.plus(ret, JExpr.lit((firstField ? " [" : ", ") + entry.getKey() + "="));
				        	ret = JOp.plus(ret, entry.getValue());
				        	firstField = false;
				        }
				        ret = JOp.plus(ret, JExpr.lit("]"));
				        body._return(ret);
				        toString.annotate(Override.class);
				    }
				};
			}
			
			@Override
			public Rule<JFieldVar, JFieldVar> getDefaultRule() {
				return new Rule<JFieldVar, JFieldVar>() {
					@Override
					public JFieldVar apply(String nodeName, JsonNode node,
							JFieldVar field, Schema currentSchema) {
						return field;
					}
				};
			}
		};
		SchemaGenerator sg = new SchemaGenerator();
		SchemaMapper sm = new SchemaMapper(rf, sg);
		for (JavaType type : data.getTypes()) {
			File jsonFile = new File(new File(jsonOutDir, type.getModuleName()), type.getJavaClassName() + ".json"); 
			URL source = jsonFile.toURI().toURL();
			sm.generate(codeModel, type.getJavaClassName(), "", source);
		}
		codeModel.build(srcOutDir, new PrintStream(new ByteArrayOutputStream()));
		TextUtils.deleteRecursively(jsonOutDir);
	}
	
	private static void generateTupleClasses(JavaData data, File srcOutDir, String packageParent) throws Exception {
		Set<Integer> tupleTypes = data.getTupleTypes();
		if (tupleTypes.size() > 0) {
			File utilDir = new File(srcOutDir.getAbsolutePath() + "/" + utilPackage.replace('.', '/'));
			if (!utilDir.exists())
				utilDir.mkdirs();
			for (int tupleType : tupleTypes) {
				if (tupleType < 1)
					throw new KidlParseException("Wrong tuple type: " + tupleType);
				File tupleFile = new File(utilDir, "Tuple" + tupleType + ".java");
				StringBuilder sb = new StringBuilder();
				for (int i = 0; i < tupleType; i++) {
					if (sb.length() > 0)
						sb.append(", ");
					sb.append('T').append(i+1);
				}
				List<String> classLines = new ArrayList<String>(Arrays.asList(
						"package " + utilPackage + ";",
						"",
						"import java.util.HashMap;",
						"import java.util.Map;",
						"import com.fasterxml.jackson.annotation.JsonAnyGetter;",
						"import com.fasterxml.jackson.annotation.JsonAnySetter;",
						"",
						"public class Tuple" + tupleType + " <" + sb + "> {"
						));
				for (int i = 0; i < tupleType; i++) {
					classLines.add("    private T" + (i + 1) + " e" + (i + 1) + ";");
				}
				classLines.add("    private Map<String, Object> additionalProperties = new HashMap<String, Object>();");
				StringBuilder toStr = new StringBuilder();
				for (int i = 0; i < tupleType; i++) {
					classLines.addAll(Arrays.asList(
							"",
							"    public T" + (i + 1) + " getE" + (i + 1) + "() {",
							"        return e" + (i + 1) + ";",
							"    }",
							"",
							"    public void setE" + (i + 1) + "(T" + (i + 1) + " e" + (i + 1) + ") {",
							"        this.e" + (i + 1) + " = e" + (i + 1) + ";",
							"    }",
							"",
							"    public Tuple" + tupleType + "<" + sb + "> withE" + (i + 1) + "(T" + (i + 1) + " e" + (i + 1) + ") {",
							"        this.e" + (i + 1) + " = e" + (i + 1) + ";",
							"        return this;",
							"    }"
							));
					if (i > 0)
						toStr.append(", ");
					toStr.append("e").append(i + 1).append("=\" + ").append("e").append(i + 1).append(" + \"");
				}
				classLines.addAll(Arrays.asList(
						"",
						"    @Override",
						"    public String toString() {",
						"        return \"Tuple" + tupleType + " [" + toStr + "]\";",
						"    }",
						"",
						"    @JsonAnyGetter",
						"    public Map<String, Object> getAdditionalProperties() {",
						"        return this.additionalProperties;",
						"    }",
						"",
						"    @JsonAnySetter",
						"    public void setAdditionalProperties(String name, Object value) {",
						"        this.additionalProperties.put(name, value);",
						"    }",
						"}"
						));
				TextUtils.writeFileLines(classLines, tupleFile);
			}
		}
	}

	private static File getParentSourceDir(File srcOutDir, String packageParent) {
		File parentDir = new File(srcOutDir.getAbsolutePath() + "/" + packageParent.replace('.', '/'));
		if (!parentDir.exists())
			parentDir.mkdirs();
		return parentDir;
	}

	private static void generateClientClass(JavaData data, File srcOutDir,
			String packageParent, URL url) throws Exception {
		Map<String, JavaType> originalToJavaTypes = getOriginalToJavaTypesMap(data);
		File parentDir = getParentSourceDir(srcOutDir, packageParent);
		for (JavaModule module : data.getModules()) {
			File moduleDir = new File(parentDir, module.getModulePackage());
			if (!moduleDir.exists())
				moduleDir.mkdir();
			JavaImportHolder model = new JavaImportHolder(packageParent + "." + module.getModulePackage());
			String clientClassName = TextUtils.capitalize(module.getModuleName()) + "Client";
			File classFile = new File(moduleDir, clientClassName + ".java");
			String callerClass = model.ref(utilPackage + ".JsonClientCaller");
			boolean anyAuth = false;
			for (JavaFunc func : module.getFuncs()) {
				if (func.isAuthCouldBeUsed()) {
					anyAuth = true;
					break;
				}
			}
			List<String> classLines = new ArrayList<String>();
			String urlClass = model.ref("java.net.URL");
			String tokenClass = model.ref("us.kbase.auth.AuthToken");
			printModuleComment(module, classLines);
			classLines.addAll(Arrays.asList(
					"public class " + clientClassName + " {",
					"    private " + callerClass + " caller;"
					));
			if (url != null) {
				classLines.addAll(Arrays.asList(
					"    private static URL DEFAULT_URL = null;",
					"    static {",
					"        try {",
					"            DEFAULT_URL = new URL(\"" + url + "\");",
					"        } catch (" + model.ref("java.net.MalformedURLException") + " mue) {",
					"            throw new RuntimeException(\"Compile error in client - bad url compiled\");",
					"        }",
					"    }",
					"",
					"    /** Constructs a client with the default url and no user credentials.*/",
					"    public " + clientClassName + "() {",
					"       caller = new " + callerClass + "(DEFAULT_URL);",
					"    }"
					));
			}
			classLines.addAll(Arrays.asList(
					"",
					"",
					"    /** Constructs a client with a custom URL and no user credentials.",
					"     * @param url the URL of the service.",
					"     */",
					"    public " + clientClassName + "(" + urlClass + " url) {",
					"        caller = new " + callerClass + "(url);",
					"    }"
					));
			if (anyAuth) {
				classLines.addAll(Arrays.asList(
						"    /** Constructs a client with a custom URL.",
						"     * @param url the URL of the service.",
						"     * @param token the user's authorization token.",
						"     * @throws UnauthorizedException if the token is not valid.",
						"     * @throws IOException if an IOException occurs when checking the token's",
						"     * validity.",
						"     */",
						"    public " + clientClassName + "(" + urlClass + " url, " + 
								model.ref("us.kbase.auth.AuthToken") + " token) throws " + 
								model.ref(utilPackage + ".UnauthorizedException") + ", " +
								model.ref("java.io.IOException") + " {",
						"        caller = new " + callerClass + "(url, token);",
						"    }",
						"",
						"    /** Constructs a client with a custom URL.",
						"     * @param url the URL of the service.",
						"     * @param user the user name.",
						"     * @param password the password for the user name.",
						"     * @throws UnauthorizedException if the credentials are not valid.",
						"     * @throws IOException if an IOException occurs when checking the user's",
						"     * credentials.",
						"     */",
						"    public " + clientClassName + "(" + urlClass + 
								" url, String user, String password) throws " + 
								model.ref(utilPackage + ".UnauthorizedException") + ", " +
								model.ref("java.io.IOException") + " {",
						"        caller = new " + callerClass + "(url, user, password);",
						"    }"
						));
				if (url != null) {
					classLines.addAll(Arrays.asList(
						"",
						"    /** Constructs a client with the default URL.",
						"     * @param token the user's authorization token.",
						"     * @throws UnauthorizedException if the token is not valid.",
						"     * @throws IOException if an IOException occurs when checking the token's",
						"     * validity.",
						"     */",
						"    public " + clientClassName + "(" + tokenClass + " token) throws " + 
								model.ref(utilPackage + ".UnauthorizedException") + ", " +
								model.ref("java.io.IOException") + " {",
						"        caller = new " + callerClass + "(DEFAULT_URL, token);",
						"    }",
						"",
						"    /** Constructs a client with the default URL.",
						"     * @param user the user name.",
						"     * @param password the password for the user name.",
						"     * @throws UnauthorizedException if the credentials are not valid.",
						"     * @throws IOException if an IOException occurs when checking the user's",
						"     * credentials.",
						"     */",
						"    public " + clientClassName + "(String user, String password) throws " + 
								model.ref(utilPackage + ".UnauthorizedException") + ", " +
								model.ref("java.io.IOException") + " {",
						"        caller = new " + callerClass + "(DEFAULT_URL, user, password);",
						"    }"
						));
				}
				classLines.addAll(Arrays.asList(
						"",
						"    /** Get the token this client uses to communicate with the server.",
						"     * @return the authorization token.",
						"     */",
						"    public " + tokenClass + " getToken() {",
						"        return caller.getToken();",
						"    }"
						));
			}
			classLines.addAll(Arrays.asList(
					"",
					"    /** Get the URL of the service with which this client communicates.",
					"     * @return the service URL.",
					"     */",
					"    public " + urlClass + " getURL() {",
					"        return caller.getURL();",
					"    }",
					"",
					"    /** Set the timeout between establishing a connection to a server and",
					"     * receiving a response. A value of zero or null implies no timeout.",
					"     * @param milliseconds the milliseconds to wait before timing out when",
					"     * attempting to read from a server.",
					"     */",
					"    public void setConnectionReadTimeOut(Integer milliseconds) {",
					"        this.caller.setConnectionReadTimeOut(milliseconds);",
					"    }",
					"",
					"    /** Check if this client allows insecure http (vs https) connections.",
					"     * @return true if insecure connections are allowed.",
					"     */",
					"    public boolean isInsecureHttpConnectionAllowed() {",
					"        return caller.isInsecureHttpConnectionAllowed();",
					"    }",
					"",
					"    /** Deprecated. Use isInsecureHttpConnectionAllowed().",
					"     * @deprecated",
					"     */",
					"    public boolean isAuthAllowedForHttp() {",
					"        return caller.isAuthAllowedForHttp();",
					"    }",
					"",
					"    /** Set whether insecure http (vs https) connections should be allowed by",
					"     * this client.",
					"     * @param allowed true to allow insecure connections. Default false",
					"     */",
					"    public void setIsInsecureHttpConnectionAllowed(boolean allowed) {",
					"        caller.setInsecureHttpConnectionAllowed(allowed);",
					"    }",
					"",
					"    /** Deprecated. Use setIsInsecureHttpConnectionAllowed().",
					"     * @deprecated",
					"     */",
					"    public void setAuthAllowedForHttp(boolean isAuthAllowedForHttp) {",
					"        caller.setAuthAllowedForHttp(isAuthAllowedForHttp);",
					"    }",
					"",
					"    /** Set whether all SSL certificates, including self-signed certificates,",
					"     * should be trusted.",
					"     * @param trustAll true to trust all certificates. Default false.",
					"     */",
					"    public void setAllSSLCertificatesTrusted(final boolean trustAll) {",
					"        caller.setAllSSLCertificatesTrusted(trustAll);",
					"    }",
					"    ",
					"    /** Check if this client trusts all SSL certificates, including",
					"     * self-signed certificates.",
					"     * @return true if all certificates are trusted.",
					"     */",
					"    public boolean isAllSSLCertificatesTrusted() {",
					"        return caller.isAllSSLCertificatesTrusted();",
					"    }",
					"    /** Sets streaming mode on. In this case, the data will be streamed to",
					"     * the server in chunks as it is read from disk rather than buffered in",
					"     * memory. Many servers are not compatible with this feature.",
					"     * @param streamRequest true to set streaming mode on, false otherwise.",
					"     */",
					"    public void setStreamingModeOn(boolean streamRequest) {",
					"        caller.setStreamingModeOn(streamRequest);",
					"    }",
					"",
					"    /** Returns true if streaming mode is on.",
					"     * @return true if streaming mode is on.",
					"     */",
					"    public boolean isStreamingModeOn() {",
					"        return caller.isStreamingModeOn();",
					"    }",
					"",
					"    public void _setFileForNextRpcResponse(" + model.ref("java.io.File") + " f) {",
					"        caller.setFileForNextRpcResponse(f);",
					"    }"
					));
			for (JavaFunc func : module.getFuncs()) {
				JavaType retType = null;
				if (func.getRetMultyType() == null) {
					if (func.getReturns().size() > 0) {
						retType = func.getReturns().get(0).getType();
					}
				} else {
					retType = func.getRetMultyType();
				}
				StringBuilder funcParams = new StringBuilder();
				for (JavaFuncParam param : func.getParams()) {
					if (funcParams.length() > 0)
						funcParams.append(", ");
					funcParams.append(getJType(param.getType(), packageParent, model)).append(" ").append(param.getJavaName());
				}
				String retTypeName = retType == null ? "void" : getJType(retType, packageParent, model);
				String listClass = model.ref("java.util.List");
				String arrayListClass = model.ref("java.util.ArrayList");
				String exceptions = "throws " + model.ref("java.io.IOException") 
						+ ", " + model.ref(utilPackage + ".JsonClientException");
				classLines.add("");
				printFuncComment(func, originalToJavaTypes, packageParent, classLines, true);
				classLines.add("    public " + retTypeName + " " + func.getJavaName() + "(" + funcParams + ") " + exceptions+ " {");
				classLines.add("        " + listClass + "<Object> args = new " + arrayListClass + "<Object>();");
				for (JavaFuncParam param : func.getParams()) {
					classLines.add("        args.add(" + param.getJavaName() + ");");
				}
				String typeReferenceClass = model.ref("com.fasterxml.jackson.core.type.TypeReference");
				boolean authRequired = func.isAuthRequired();
				boolean needRet = retType != null;
				if (func.getRetMultyType() == null) {
					if (retType == null) {
						String trFull = typeReferenceClass + "<Object>";
						classLines.addAll(Arrays.asList(
								"        " + trFull + " retType = new " + trFull + "() {};",
								"        caller.jsonrpcCall(\"" + module.getOriginal().getModuleName() + "." + func.getOriginal().getName() + "\", args, retType, " + needRet + ", " + authRequired + ");",
								"    }"
								));
					} else {
						String trFull = typeReferenceClass + "<" + listClass + "<" + retTypeName + ">>";
						classLines.addAll(Arrays.asList(
								"        " + trFull + " retType = new " + trFull + "() {};",
								"        " + listClass + "<" + retTypeName + "> res = caller.jsonrpcCall(\"" + module.getOriginal().getModuleName() + "." + func.getOriginal().getName() + "\", args, retType, " + needRet + ", " + authRequired + ");",
								"        return res.get(0);",
								"    }"
								));
					}
				} else {
					String trFull = typeReferenceClass + "<" + retTypeName + ">";
					classLines.addAll(Arrays.asList(
							"        " + trFull + " retType = new " + trFull + "() {};",
							"        " + retTypeName + " res = caller.jsonrpcCall(\"" + module.getOriginal().getModuleName() + "." + func.getOriginal().getName() + "\", args, retType, " + needRet + ", " + authRequired + ");",
							"        return res;",
							"    }"
							));					
				}
			}
			classLines.add("}");
			List<String> headerLines = new ArrayList<String>(Arrays.asList(
					"package " + packageParent + "." + module.getModulePackage() + ";",
					""
					));
			headerLines.addAll(model.generateImports());
			headerLines.add("");
			classLines.addAll(0, headerLines);
			TextUtils.writeFileLines(classLines, classFile);
		}
	}

	private static void printFuncComment(JavaFunc func, Map<String, JavaType> originalToJavaTypes, 
			String packageParent, List<String> classLines, boolean client) {
		List<String> funcCommentLines = new ArrayList<String>();
		funcCommentLines.add("<p>Original spec-file function name: " + func.getOriginal().getName() + "</p>");
		funcCommentLines.add("<pre>");
		funcCommentLines.addAll(parseCommentLines(func.getOriginal().getComment()));
		funcCommentLines.add("</pre>");
		for (JavaFuncParam param : func.getParams()) {
			String descr = getTypeDescr(originalToJavaTypes, param.getOriginal().getType(), packageParent, null);
			funcCommentLines.add("@param   " + param.getJavaName() + "   " + descr);
		}
		if (func.getReturns().size() > 0) {
			if (func.getRetMultyType() == null) {
				JavaFuncParam ret = func.getReturns().get(0);
				String descr = getTypeDescr(originalToJavaTypes, ret.getOriginal().getType(), packageParent, 
						ret.getOriginal().getName());
				funcCommentLines.add("@return   " + descr);
			} else {
				StringBuilder descr = new StringBuilder();
				for (int i = 0; i < func.getReturns().size(); i++) {
					JavaFuncParam ret = func.getReturns().get(i);
					if (descr.length() > 0)
						descr.append(", ");
					descr.append('(').append((i + 1)).append(") ");
					descr.append(getTypeDescr(originalToJavaTypes, ret.getOriginal().getType(), packageParent, 
							ret.getOriginal().getName()));
				}
				funcCommentLines.add("@return   multiple set: " + descr);
			}
		}
		if (client) {
			funcCommentLines.add("@throws IOException if an IO exception occurs");
			funcCommentLines.add("@throws JsonClientException if a JSON RPC exception occurs");
		}
		printCommentLines("    ", funcCommentLines, classLines);
	}

	private static String getTypeDescr(Map<String, JavaType> originalToJavaTypes,
			KbType type, String packageParent, String paramName) {
		StringBuilder sb = new StringBuilder();
		if (paramName == null)
			sb.append("instance of ");
		createTypeDescr(originalToJavaTypes, type, packageParent, paramName, sb);
		return sb.toString();
	}
	
	private static void createTypeDescr(Map<String, JavaType> originalToJavaTypes,
			KbType type, String packageParent, String paramName, StringBuilder sb) {
		if (paramName != null)
			sb.append("parameter \"").append(paramName).append("\" of ");
		if (type instanceof KbTypedef) {
			KbTypedef ref = (KbTypedef)type;
			String originalTypeKey = TextUtils.capitalize(ref.getModule()).toLowerCase() + "." + ref.getName();
			if (originalToJavaTypes.containsKey(originalTypeKey)) {
				JavaType refJavaType = originalToJavaTypes.get(originalTypeKey);
				sb.append("type {@link ").append(getPackagePrefix(packageParent, refJavaType))
				.append(refJavaType.getJavaClassName()).append(' ').append(refJavaType.getJavaClassName())
				.append("}");
				if (!refJavaType.getJavaClassName().equals(ref.getName()))
					sb.append(" (original type \"").append(ref.getName()).append("\")");
			} else {
				sb.append("original type \"").append(ref.getName()).append("\"");
				List<String> refCommentLines = parseCommentLines(ref.getComment());
				if (refCommentLines.size() > 0) {
					StringBuilder concatLines = new StringBuilder();
					for (String l : refCommentLines) {
						if (concatLines.length() > 0 && concatLines.charAt(concatLines.length() - 1) != ' ')
							concatLines.append(' ');
						concatLines.append(l.trim());
					}
					sb.append(" (").append(concatLines).append(")");
				}
				if (!(ref.getAliasType() instanceof KbScalar)) {
					sb.append(" &rarr; ");
					createTypeDescr(originalToJavaTypes, ref.getAliasType(), packageParent, null, sb);
				}
			}
		} else if (type instanceof KbStruct) {
			throw new IllegalStateException("Inline structures are not allowed in spec-syntax");
		} else if (type instanceof KbList) {
			KbList list = (KbList)type;
			sb.append("list of ");
			createTypeDescr(originalToJavaTypes, list.getElementType(), packageParent, null, sb);			
		} else if (type instanceof KbMapping) {
			KbMapping map = (KbMapping)type;
			sb.append("mapping from ");
			createTypeDescr(originalToJavaTypes, map.getKeyType(), packageParent, null, sb);
			sb.append(" to ");
			createTypeDescr(originalToJavaTypes, map.getValueType(), packageParent, null, sb);
		} else if (type instanceof KbTuple) {
			KbTuple tuple = (KbTuple)type;
			sb.append("tuple of size ").append(tuple.getElementTypes().size()).append(": ");
			for (int i = 0; i < tuple.getElementTypes().size(); i++) {
				if (i > 0)
					sb.append(", ");
				String tupleParamName = tuple.getElementNames().get(i);
				if (tupleParamName.equals("e_" + (i + 1)))
					tupleParamName = null;
				createTypeDescr(originalToJavaTypes, tuple.getElementTypes().get(i), packageParent, 
						tupleParamName, sb);		
			}
		} else if (type instanceof KbUnspecifiedObject) {
			sb.append("unspecified object");
		} else if (type instanceof KbScalar) {
			sb.append(((KbScalar)type).getJavaStyleName());
		}
	}

	private static Map<String, JavaType> getOriginalToJavaTypesMap(JavaData data) {
		Map<String, JavaType> originalToJavaTypes = new HashMap<String, JavaType>();
		for (JavaType type : data.getTypes()) {
			originalToJavaTypes.put(type.getModuleName() + "." + type.getOriginalTypeName(), type);
		}
		return originalToJavaTypes;
	}

	private static void printModuleComment(JavaModule module, List<String> classLines) {
		List<String> lines = new ArrayList<String>();
		lines.add("<p>Original spec-file module name: " + module.getOriginal().getModuleName() + "</p>");
		lines.add("<pre>");
		lines.addAll(parseCommentLines(module.getOriginal().getComment()));
		lines.add("</pre>");
		printCommentLines("", lines, classLines);
	}
	
	private static HashMap<String, String> parsePrevCode(File classFile, List<JavaFunc> funcs)
		throws IOException, ParseException {
	    List<String> funcNames = new ArrayList<String>();
		for (JavaFunc func: funcs)
			funcNames.add(func.getOriginal().getName());
		return PrevCodeParser.parsePrevCode(classFile, "//", funcNames, true);
	}

	private static List<String> splitCodeLines(String code) {
		LinkedList<String> l = new LinkedList<String>();
		if (code.length() == 0) { //returns empty string otherwise
			return l;
		}
		return Arrays.asList(code.split("\n"));
	}
	
	private static void generateServerClass(JavaData data, File srcOutDir, String packageParent) throws Exception {
		Map<String, JavaType> originalToJavaTypes = getOriginalToJavaTypesMap(data);
		File parentDir = getParentSourceDir(srcOutDir, packageParent);
		for (JavaModule module : data.getModules()) {
			File moduleDir = new File(parentDir, module.getModulePackage());
			if (!moduleDir.exists())
				moduleDir.mkdir();
			JavaImportHolder model = new JavaImportHolder(packageParent + "." + module.getModulePackage());
			String serverClassName = TextUtils.capitalize(module.getModuleName()) + "Server";
			File classFile = new File(moduleDir, serverClassName + ".java");
			HashMap<String, String> originalCode = parsePrevCode(classFile, module.getFuncs());
			List<String> classLines = new ArrayList<String>();
			printModuleComment(module, classLines);
			classLines.addAll(Arrays.asList(
					"public class " + serverClassName + " extends " + model.ref(utilPackage + ".JsonServerServlet") + " {",
					"    private static final long serialVersionUID = 1L;",
					""
					));
			classLines.add("    //BEGIN_CLASS_HEADER");
			classLines.addAll(splitCodeLines(originalCode.get(PrevCodeParser.CLSHEADER)));
			classLines.add("    //END_CLASS_HEADER");
			classLines.add("");
			classLines.add("    public " + serverClassName + "() throws Exception {");
			String serviceName = module.getOriginal().getServiceName();
			if (serviceName == null || serviceName.trim().isEmpty())
				serviceName = module.getOriginal().getModuleName();
			classLines.add("        super(\"" + serviceName + "\");");
			classLines.add("        //BEGIN_CONSTRUCTOR");
			classLines.addAll(splitCodeLines(originalCode.get(PrevCodeParser.CONSTRUCTOR)));
			classLines.addAll(Arrays.asList(
					"        //END_CONSTRUCTOR",
					"    }"
					));
			for (JavaFunc func : module.getFuncs()) {
				JavaType retType = null;
				if (func.getRetMultyType() == null) {
					if (func.getReturns().size() > 0) {
						retType = func.getReturns().get(0).getType();
					}
				} else {
					retType = func.getRetMultyType();
				}
				StringBuilder funcParams = new StringBuilder();
				for (JavaFuncParam param : func.getParams()) {
					if (funcParams.length() > 0)
						funcParams.append(", ");
					funcParams.append(getJType(param.getType(), packageParent, model)).append(" ").append(param.getJavaName());
				}
				if (func.isAuthCouldBeUsed()) {
					if (funcParams.length() > 0)
						funcParams.append(", ");
					funcParams.append(model.ref("us.kbase.auth.AuthToken")).append(" authPart");;					
				}
				String retTypeName = retType == null ? "void" : getJType(retType, packageParent, model);
				classLines.add("");
				printFuncComment(func, originalToJavaTypes, packageParent, classLines, false);
				classLines.add("    @" + model.ref(utilPackage + ".JsonServerMethod") + "(rpc = \"" + module.getOriginal().getModuleName() + "." + func.getOriginal().getName() + "\"" +
						(func.getRetMultyType() == null ? "" : ", tuple = true") + (func.isAuthOptional() ? ", authOptional=true" : "") + ")");
				classLines.add("    public " + retTypeName + " " + func.getJavaName() + "(" + funcParams + ") throws Exception {");
				
				List<String> funcLines = new LinkedList<String>();
				String name = func.getOriginal().getName();
				if (originalCode.containsKey(PrevCodeParser.METHOD + name)) {
					funcLines.addAll(splitCodeLines(originalCode.get(PrevCodeParser.METHOD + name)));
				}
				funcLines.add(0, "        //BEGIN " + name);
				funcLines.add("        //END " + name);
				
				if (func.getRetMultyType() == null) {
					if (retType == null) {
						classLines.addAll(funcLines);
						classLines.add("    }");
					} else {
						classLines.add("        " + retTypeName + " returnVal = null;");
						classLines.addAll(funcLines);
						classLines.addAll(Arrays.asList(
								"        return returnVal;",
								"    }"
								));
					}
				} else {
					for (int retPos = 0; retPos < func.getReturns().size(); retPos++) {
						String retInnerType = getJType(func.getReturns().get(retPos).getType(), packageParent, model);
						classLines.add("        " + retInnerType + " return" + (retPos + 1) + " = null;");
					}
					classLines.addAll(funcLines);
					classLines.add("        " + retTypeName + " returnVal = new " + retTypeName + "();");
					for (int retPos = 0; retPos < func.getReturns().size(); retPos++) {
						classLines.add("        returnVal.setE" + (retPos + 1) + "(return" + (retPos + 1) + ");");
					}					
					classLines.add("        return returnVal;");
					classLines.add("    }");					
				}
			}
			classLines.addAll(Arrays.asList(
					"",
					"    public static void main(String[] args) throws Exception {",
					"        if (args.length != 1) {",
					"            System.out.println(\"Usage: <program> <server_port>\");",
					"            return;",
					"        }",
					"        new " + serverClassName + "().startupServer(Integer.parseInt(args[0]));",
					"    }",
					"}"));
			List<String> headerLines = new ArrayList<String>(Arrays.asList(
					"package " + packageParent + "." + module.getModulePackage() + ";",
					""
					));
			headerLines.addAll(model.generateImports());
			headerLines.add("");
			headerLines.add("//BEGIN_HEADER");
			headerLines.addAll(splitCodeLines(originalCode.get(PrevCodeParser.HEADER)));
			headerLines.add("//END_HEADER");
			headerLines.add("");
			classLines.addAll(0, headerLines);
			TextUtils.writeFileLines(classLines, classFile);
		}
	}

	private static void printCommentLines(String intend, List<String> commentLines, List<String> classLines) {
		if (commentLines.size() > 0) {
			classLines.add(intend + "/**");
			for (String commentLine : commentLines) {
				classLines.add(intend + " * " + commentLine);
			}
			classLines.add(intend + " */");
		}
	}

	private static List<String> parseCommentLines(String comment) {
		List<String> commentLines = new ArrayList<String>();
		if (comment != null && comment.trim().length() > 0) {
			StringTokenizer st = new StringTokenizer(comment, "\r\n");
			while (st.hasMoreTokens()) {
				commentLines.add(st.nextToken());
			}
			removeEmptyLinesOnSides(commentLines);
		}
		return commentLines;
	}
	
	private static void removeEmptyLinesOnSides(List<String> lines) {
		while (lines.size() > 0 && lines.get(0).trim().length() == 0)
			lines.remove(0);
		while (lines.size() > 0 && lines.get(lines.size() - 1).trim().length() == 0)
			lines.remove(lines.size() - 1);
	}
	
	private static void checkLibs(File libOutDir, boolean createServers) throws Exception {
		if (libOutDir == null)
			return;
		if (!libOutDir.exists())
			libOutDir.mkdirs();
		checkLib(libOutDir, "jackson-annotations-2.2.3");
		checkLib(libOutDir, "jackson-core-2.2.3");
		checkLib(libOutDir, "jackson-databind-2.2.3");
		checkLib(libOutDir, "kbase-auth");
		checkLib(libOutDir, "kbase-common");
		if (createServers) {
			checkLib(libOutDir, "servlet-api-2.5");
			checkLib(libOutDir, "jetty-all-7.0.0");
			checkLib(libOutDir, "ini4j-0.5.2");
			checkLib(libOutDir, "syslog4j-0.9.46");
			checkLib(libOutDir, "jna-3.4.0");
		}
	}
	
	public static void checkLib(File libDir, String libName) throws Exception {
		File libFile = null;
		for (URL url : ((URLClassLoader)(Thread.currentThread().getContextClassLoader())).getURLs()) {
			File maybelibFile = new File(url.getPath());
			if (maybelibFile.isFile() && maybelibFile.getName().startsWith(libName) && 
					maybelibFile.getName().endsWith(".jar"))
				libFile = maybelibFile;
		}
		if (libFile == null)
			throw new KidlParseException("Can't find lib-file for: " + libName);
		InputStream is = new FileInputStream(libFile);
		OutputStream os = new FileOutputStream(new File(libDir, libName + ".jar"));
		TextUtils.copyStreams(is, os);
	}
	
	private static void writeJsonSchema(File jsonFile, String packageParent, JavaType type, 
			Set<Integer> tupleTypes) throws Exception {
		LinkedHashMap<String, Object> tree = new LinkedHashMap<String, Object>();
		tree.put("$schema", "http://json-schema.org/draft-04/schema#");
		tree.put("id", type.getModuleName() + "." + type.getJavaClassName());
		StringBuilder descr = new StringBuilder("<p>Original spec-file type: ").append(type.getOriginalTypeName()).append("</p>\n");
		List<String> descrLines = new ArrayList<String>();
		if (type.getAliasHistoryOuterToDeep().size() > 0) {
			descrLines.addAll(parseCommentLines(type.getAliasHistoryOuterToDeep().get(0).getComment()));
			if (descrLines.size() > 0) {
				descr.append("<pre>\n");
				for (String l : descrLines) {
					descr.append(l).append("\n");
				}
				descr.append("</pre>");
			}
		}
		tree.put("description", descr.toString());
		tree.put("type", "object");
		tree.put("javaType", packageParent + "." + type.getModuleName() + "." + type.getJavaClassName());
		if (type.getMainType() instanceof KbStruct) {
			LinkedHashMap<String, Object> props = new LinkedHashMap<String, Object>();
			for (int itemPos = 0; itemPos < type.getInternalTypes().size(); itemPos++) {
				JavaType iType = type.getInternalTypes().get(itemPos);
				String field = type.getInternalFields().get(itemPos);
				props.put(field, createJsonRefTypeTree(type.getModuleName(), iType, 
						type.getInternalComment(itemPos), false, packageParent, tupleTypes));
			}
			tree.put("properties", props);
			tree.put("additionalProperties", true);
		} else {
			throw new KidlParseException("Type " + type.getMainType().getClass().getSimpleName() + " is not " +
					"supported for POJO generation");
		}
		ObjectMapper mapper = new ObjectMapper();
		mapper.configure(SerializationFeature.INDENT_OUTPUT, true);
		mapper.writeValue(jsonFile, tree);
	}

	private static LinkedHashMap<String, Object> createJsonRefTypeTree(String module, JavaType type, String comment, 
			boolean insideTypeParam, String packageParent, Set<Integer> tupleTypes) {
		LinkedHashMap<String, Object> typeTree = new LinkedHashMap<String, Object>();
		if (comment != null && comment.trim().length() > 0)
			typeTree.put("description", comment);
		if (type.needClassGeneration()) {
			if (insideTypeParam) {
				typeTree.put("type", "object");
				typeTree.put("javaType", packageParent + "." + type.getModuleName() + "." + type.getJavaClassName());
			} else {
				String modulePrefix = type.getModuleName().equals(module) ? "" : ("../" + type.getModuleName() + "/");
				typeTree.put("$ref", modulePrefix + type.getJavaClassName() + ".json");
			}
		} else if (type.getMainType() instanceof KbScalar) {
			if (insideTypeParam) {
				typeTree.put("type", "object");
				typeTree.put("javaType", ((KbScalar)type.getMainType()).getJavaStyleName());
			} else {
				typeTree.put("type", ((KbScalar)type.getMainType()).getJsonStyleName());
			}
		} else if (type.getMainType() instanceof KbList) {
			LinkedHashMap<String, Object> subType = createJsonRefTypeTree(module, type.getInternalTypes().get(0), null, 
					true, packageParent, tupleTypes);
				typeTree.put("type", "object");
				typeTree.put("javaType", "java.util.List");
				typeTree.put("javaTypeParams", subType);
		} else if (type.getMainType() instanceof KbMapping) {
			typeTree.put("type", "object");
			typeTree.put("javaType", "java.util.Map");
			List<LinkedHashMap<String, Object>> subList = new ArrayList<LinkedHashMap<String, Object>>();
			for (JavaType iType : type.getInternalTypes())
				subList.add(createJsonRefTypeTree(module, iType, null, true, packageParent, tupleTypes));
			typeTree.put("javaTypeParams", subList);
		} else if (type.getMainType() instanceof KbTuple) {
			typeTree.put("type", "object");
			int tupleType = type.getInternalTypes().size();
			if (tupleType < 1)
				throw new IllegalStateException("Wrong count of tuple parameters: " + tupleType);
			typeTree.put("javaType", utilPackage + ".Tuple" + tupleType);
			tupleTypes.add(tupleType);
			List<LinkedHashMap<String, Object>> subList = new ArrayList<LinkedHashMap<String, Object>>();
			for (JavaType iType : type.getInternalTypes())
				subList.add(createJsonRefTypeTree(module, iType, null, true, packageParent, tupleTypes));
			typeTree.put("javaTypeParams", subList);
		} else if (type.getMainType() instanceof KbUnspecifiedObject) {
			typeTree.put("type", "object");
			typeTree.put("javaType", utilPackage + ".UObject");
		} else {
			throw new IllegalStateException("Unknown type: " + type.getMainType().getClass().getName());
		}
		return typeTree;
	}

	private static JavaType findBasic(KbType type, String moduleName, Set<JavaType> nonPrimitiveTypes, Set<Integer> tupleTypes) {
		JavaType ret = findBasic(null, type, moduleName, null, new ArrayList<KbTypedef>(), nonPrimitiveTypes, tupleTypes);
		return ret;
	}

	private static JavaType findBasic(String typeName, KbType type, String defaultModuleName, String typeModuleName, 
			List<KbTypedef> aliases, Set<JavaType> nonPrimitiveTypes, Set<Integer> tupleTypes) {
		if (type instanceof KbBasicType) {
			JavaType ret = new JavaType(typeName, (KbBasicType)type, 
					typeModuleName == null ? defaultModuleName : typeModuleName, aliases);
			if (!(type instanceof KbScalar || type instanceof KbUnspecifiedObject))
				if (type instanceof KbStruct) {
					for (KbStructItem item : ((KbStruct)type).getItems()) {
						ret.addInternalType(findBasic(null, item.getItemType(), defaultModuleName, null, 
								new ArrayList<KbTypedef>(), nonPrimitiveTypes, tupleTypes));
						ret.addInternalField(item.getName(), "");
					}
				} else if (type instanceof KbList) {
					ret.addInternalType(findBasic(null, ((KbList)type).getElementType(), defaultModuleName, null, 
							new ArrayList<KbTypedef>(), nonPrimitiveTypes, tupleTypes));
				} else if (type instanceof KbMapping) {
					ret.addInternalType(findBasic(null, ((KbMapping)type).getKeyType(), defaultModuleName, null, 
							new ArrayList<KbTypedef>(), nonPrimitiveTypes, tupleTypes));
					ret.addInternalType(findBasic(null, ((KbMapping)type).getValueType(), defaultModuleName, null, 
							new ArrayList<KbTypedef>(), nonPrimitiveTypes, tupleTypes));
				} else if (type instanceof KbTuple) {
					tupleTypes.add(((KbTuple)type).getElementTypes().size());
					for (KbType iType : ((KbTuple)type).getElementTypes())
						ret.addInternalType(findBasic(null, iType, defaultModuleName, null, 
								new ArrayList<KbTypedef>(), nonPrimitiveTypes, tupleTypes));
				} else {
					throw new IllegalStateException("Unknown basic type: " + type.getClass().getSimpleName());
				}
			if (ret.needClassGeneration())
				nonPrimitiveTypes.add(ret);
			return ret;
		} else {
			KbTypedef typeRef = (KbTypedef)type;
			aliases.add(typeRef);
			return findBasic(typeRef.getName(), typeRef.getAliasType(), defaultModuleName, typeRef.getModule(), 
					aliases, nonPrimitiveTypes, tupleTypes);
		}
	}

	private static String getJType(JavaType type, String packageParent, JavaImportHolder codeModel) throws Exception {
		KbBasicType kbt = type.getMainType();
		if (type.needClassGeneration()) {
			return codeModel.ref(getPackagePrefix(packageParent, type) + type.getJavaClassName());
		} else if (kbt instanceof KbScalar) {
			return codeModel.ref(((KbScalar)kbt).getFullJavaStyleName());
		} else if (kbt instanceof KbList) {
			return codeModel.ref("java.util.List") + "<" + getJType(type.getInternalTypes().get(0), packageParent, codeModel) + ">";
		} else if (kbt instanceof KbMapping) {
			return codeModel.ref("java.util.Map")+ "<" + getJType(type.getInternalTypes().get(0), packageParent, codeModel) + "," +
					getJType(type.getInternalTypes().get(1), packageParent, codeModel) + ">";
		} else if (kbt instanceof KbTuple) {
			int paramCount = type.getInternalTypes().size();
			StringBuilder narrowParams = new StringBuilder();
			for (JavaType iType : type.getInternalTypes()) {
				if (narrowParams.length() > 0)
					narrowParams.append(", ");
				narrowParams.append(getJType(iType, packageParent, codeModel));
			}
			return codeModel.ref(utilPackage + ".Tuple" + paramCount) + "<" + narrowParams + ">";
		} else if (kbt instanceof KbUnspecifiedObject) {
			return codeModel.ref(utilPackage + ".UObject");
	    } else {
			throw new IllegalStateException("Unknown data type: " + kbt.getClass().getName());
		}
	}

	private static String getPackagePrefix(String packageParent, JavaType type) {
		return packageParent + "." + type.getModuleName() + ".";
	}
	
	public static class Args {
		@Option(name="-o",usage="Output folder (src and lib subfolders will be created), use -s and possibly -l instead of -o for more detailed settings", metaVar="<out-dir>")
		String outputDir;

		@Option(name="-s",usage="Source output folder (exclusive with -o)", metaVar="<src-dir>")
		String srcDir;

		@Option(name="-l",usage="Library output folder (exclusive with -o, not required when using -s)", metaVar="<lib-dir>")
		String libDir;
		
		@Option(name="-u", usage="Default url for service", metaVar="<url>")
		String url = null;

		@Option(name="-p",usage="Java package parent (module subpackages are created in this package), default value is " + defaultParentPackage, metaVar="<package>")		
		String packageParent = defaultParentPackage;

		@Option(name="-t", usage="Temporary folder, default value is parent folder of <spec-file>", metaVar="<tmp-dir>")
		String tempDir;
		
		@Option(name="-S", usage="Defines whether or not java code for server side should be created, default value is false, use -S for true")
		boolean createServerSide = false;

		@Option(name="-g",usage="Gwt client java package (define it in case you need copies of generated classes for GWT client)", metaVar="<gwtpckg>")		
		String gwtPackage = null;

		@Argument(metaVar="<spec-file>",required=true,usage="File *.spec for compilation into java classes")
		File specFile;
	}
}

