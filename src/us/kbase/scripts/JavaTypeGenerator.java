package us.kbase.scripts;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.text.MessageFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringTokenizer;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.codehaus.jackson.map.ObjectMapper;
import org.codehaus.jackson.map.SerializationConfig.Feature;
import org.kohsuke.args4j.Argument;
import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.Option;

import us.kbase.scripts.util.ProcessHelper;

import com.googlecode.jsonschema2pojo.DefaultGenerationConfig;
import com.googlecode.jsonschema2pojo.Jackson1Annotator;
import com.googlecode.jsonschema2pojo.SchemaGenerator;
import com.googlecode.jsonschema2pojo.SchemaMapper;
import com.googlecode.jsonschema2pojo.SchemaStore;
import com.googlecode.jsonschema2pojo.rules.Rule;
import com.googlecode.jsonschema2pojo.rules.RuleFactory;
import com.sun.codemodel.JCodeModel;
import com.sun.codemodel.JPackage;
import com.sun.codemodel.JType;

public class JavaTypeGenerator {
	private static final char[] propWordDelim = {'_', '-'};
	private static final String utilPackage = "us.kbase";
	
	private static final String HEADER = "HEADER";
	private static final String CLSHEADER = "CLASS_HEADER";
	private static final String CONSTRUCTOR = "CONSTRUCTOR";
	private static final String METHOD = "METHOD_";
	
	private static final Pattern PAT_HEADER = Pattern.compile(
			".*//BEGIN_HEADER\n(.*)//END_HEADER\n.*", Pattern.DOTALL);
	private static final Pattern PAT_CLASS_HEADER = Pattern.compile(
			".*//BEGIN_CLASS_HEADER\n(.*)    //END_CLASS_HEADER\n.*", Pattern.DOTALL);
	private static final Pattern PAT_CONSTRUCTOR = Pattern.compile(
			".*//BEGIN_CONSTRUCTOR\n(.*)        //END_CONSTRUCTOR\n.*", Pattern.DOTALL);

	private static final boolean useJsyncForParsing = true;
	
	public static void main(String[] args) throws Exception {
		Args parsedArgs = new Args();
		CmdLineParser parser = new CmdLineParser(parsedArgs);
		parser.setUsageWidth(85);
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
		processSpec(inputFile, tempDir, srcOutDir, packageParent, createServer, libDir, parsedArgs.gwtPackage);
		if (deleteTempDir)
			tempDir.delete();
	}

	private static void showUsage(CmdLineParser parser, String message) {
		System.err.println(message);
		System.err.println("Usage: <program> [options...] <spec-file>");
		parser.printUsage(System.err);
	}
	
	public static JavaData processSpec(File specFile, File tempDir, File srcOutDir, String packageParent, 
			boolean createServer, File libOutDir, String gwtPackage) throws Exception {		
		return processParsingFile(transformSpecToJson(specFile, tempDir, useJsyncForParsing), new File(tempDir, "json-schemas"), 
				srcOutDir, packageParent, createServer, libOutDir, gwtPackage, useJsyncForParsing);
	}
	
	public static File transformSpecToJson(File specFile, File tempDir, boolean jsync) throws Exception {
		File bashFile = new File(tempDir, "comp_server.sh");
		File serverOutDir = new File(tempDir, "server_out");
		serverOutDir.mkdir();
		File specDir = specFile.getAbsoluteFile().getParentFile();
		File retFile = new File(tempDir, "parsing_file." + (jsync ? "json" : "xml"));
		File outFile = new File(tempDir, "comp.out");
		File errFile = new File(tempDir, "comp.err");
		List<String> lines = new ArrayList<String>(Arrays.asList("#!/bin/bash"));
		checkEnvVars(lines, "PERL5LIB");
		lines.addAll(Arrays.asList(
				"COMP_EXEC=\"perl $KB_TOP/plbin/compile_typespec.pl\"",
				"if [ ! -f $KB_TOP/plbin/compile_typespec.pl ]",
				"then",
				"    COMP_EXEC=$KB_TOP/bin/compile_typespec",
				"fi",
				"$COMP_EXEC --path \"" + specDir.getAbsolutePath() + "\"" +
				" --" + (jsync ? "jsync" : "xml") + " " + retFile.getName() + " " +
				"\"" + specFile.getAbsolutePath() + "\" " + 
				serverOutDir.getName() + " >" + outFile.getName() + " 2>" + errFile.getName()
				));
		Utils.writeFileLines(lines, bashFile);
		ProcessHelper.cmd("bash", bashFile.getCanonicalPath()).exec(tempDir);
		File jsyncFile = new File(serverOutDir, retFile.getName());
		if (jsyncFile.exists()) {
			Utils.writeFileLines(Utils.readFileLines(jsyncFile), retFile);
		} else {
			List<String> errLines = Utils.readFileLines(errFile);
			if (errLines.size() > 1 || (errLines.size() == 1 && errLines.get(0).trim().length() > 0)) {
				for (String errLine : errLines)
					System.err.println(errLine);
			}
		}
		bashFile.delete();
		Utils.deleteRecursively(serverOutDir);
		outFile.delete();
		errFile.delete();
		if (!retFile.exists()) {
			throw new IllegalStateException("Parsing file wasn't created, see error lines above for detailes");
		}
		return retFile;
	}

	public static List<String> checkEnvVars(List<String> lines, String libVar) {
		String deplPath = checkEnvVarIsSet("KB_TOP", lines, "/kb/deployment");
		String rtPath = checkEnvVarIsSet("KB_RUNTIME", lines, "/kb/runtime");
		checkEnvVarIncludes("PATH", lines, rtPath + "/bin", deplPath + "/bin");
		checkEnvVarIncludes(libVar, lines, deplPath + "/lib");
		return lines;
	}
	
	private static void checkEnvVarIncludes(String varName, List<String> shellLines, String... partPath) {
		String value = System.getenv(varName);
		Set<String> paths = new HashSet<String>();
		if (value != null) {
			String[] parts = value.split(":");
			for (String part : parts)
				if (part.trim().length() > 0)
					paths.add(part.trim());
		}
		StringBuilder newValue = new StringBuilder();
		for (String path : partPath)
			if (!paths.contains(path))
				newValue.append(path).append(":");
		if (newValue.length() > 0)
			shellLines.add("export " + varName + "=" + newValue.append("$").append(varName));
	}

	private static String checkEnvVarIsSet(String varName, List<String> shellLines, String defaultValue) {
		String value = System.getenv(varName);
		if (value == null || value.trim().length() == 0) {
			shellLines.add("export " + varName + "=" + defaultValue);
			value = defaultValue;
		}
		return value;
	}

	private static JavaData processParsingFile(File parsingFile, File jsonSchemaOutDir, File srcOutDir, String packageParent, 
			boolean createServer, File libOutDir, String gwtPackage, boolean jsync) throws Exception {		
		Map<?,?> map = null;
		if (jsync) {
			ObjectMapper mapper = new ObjectMapper();
			mapper.configure(Feature.INDENT_OUTPUT, true);
			map = mapper.readValue(parsingFile, Map.class);
		} else {
			map = SpecXmlHelper.parseXml(parsingFile);
		}
		JSyncProcessor subst = new JSyncProcessor(map);
		List<KbService> srvList = KbService.loadFromMap(map, subst);
		JavaData data = prepareDataStructures(srvList);
		parsingFile.delete();
		outputData(data, jsonSchemaOutDir, srcOutDir, packageParent, createServer, libOutDir, gwtPackage);
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
						String funcJavaName = Utils.inCamelCase(func.getName());
						List<JavaFuncParam> params = new ArrayList<JavaFuncParam>();
						for (KbParameter param : func.getParameters()) {
							JavaType type = findBasic(param.getType(), module.getModuleName(), nonPrimitiveTypes, tupleTypes);
							params.add(new JavaFuncParam(param, Utils.inCamelCase(param.getName()), type));
						}
						List<JavaFuncParam> returns = new ArrayList<JavaFuncParam>();
						for (KbParameter param : func.getReturnType()) {
							JavaType type = findBasic(param.getType(), module.getModuleName(), nonPrimitiveTypes, tupleTypes);
							returns.add(new JavaFuncParam(param, param.getName() == null ? null : Utils.inCamelCase(param.getName()), type));
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
			boolean createServers, File libOutDir, String gwtPackage) throws Exception {
		if (!srcOutDir.exists())
			srcOutDir.mkdirs();
		generatePojos(data, jsonOutDir, srcOutDir, packageParent);
		generateTupleClasses(data,srcOutDir, packageParent);
		generateClientClass(data, srcOutDir, packageParent);
		if (createServers)
			generateServerClass(data, srcOutDir, packageParent);
		checkUtilityClasses(srcOutDir, createServers);
		checkLibs(libOutDir, createServers);
		if (gwtPackage != null) {
			GwtGenerator.generate(data, srcOutDir, gwtPackage);
			checkUtilityClass(srcOutDir, "GwtTransformer");
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
		};
		SchemaStore ss = new SchemaStore();
		RuleFactory rf = new RuleFactory(cfg, new Jackson1Annotator(), ss) {
			@Override
			public Rule<JPackage, JType> getObjectRule() {
				return new JsonSchemaToPojoCustomObjectRule(this);
			}
		};
		SchemaGenerator sg = new SchemaGenerator();
		SchemaMapper sm = new SchemaMapper(rf, sg);
		for (JavaType type : data.getTypes()) {
			File jsonFile = new File(new File(jsonOutDir, type.getModuleName()), type.getJavaClassName() + ".json"); 
			URL source = jsonFile.toURI().toURL();
			sm.generate(codeModel, type.getJavaClassName(), "", source);
		}
		codeModel.build(srcOutDir);
		Utils.deleteRecursively(jsonOutDir);
	}
	
	private static void generateTupleClasses(JavaData data, File srcOutDir, String packageParent) throws Exception {
		Set<Integer> tupleTypes = data.getTupleTypes();
		if (tupleTypes.size() > 0) {
			File utilDir = new File(srcOutDir.getAbsolutePath() + "/" + utilPackage.replace('.', '/'));
			if (!utilDir.exists())
				utilDir.mkdirs();
			for (int tupleType : tupleTypes) {
				if (tupleType < 1)
					throw new IllegalStateException("Wrong tuple type: " + tupleType);
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
						"import org.codehaus.jackson.annotate.JsonAnyGetter;",
						"import org.codehaus.jackson.annotate.JsonAnySetter;",
						"",
						"public class Tuple" + tupleType + " <" + sb + "> {"
						));
				for (int i = 0; i < tupleType; i++) {
					classLines.add("    private T" + (i + 1) + " e" + (i + 1) + ";");
				}
				classLines.add("    private Map<String, Object> additionalProperties = new HashMap<String, Object>();");
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
				}
				classLines.addAll(Arrays.asList(
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
				Utils.writeFileLines(classLines, tupleFile);
			}
		}
	}

	private static File getParentSourceDir(File srcOutDir, String packageParent) {
		File parentDir = new File(srcOutDir.getAbsolutePath() + "/" + packageParent.replace('.', '/'));
		if (!parentDir.exists())
			parentDir.mkdirs();
		return parentDir;
	}

	private static void generateClientClass(JavaData data, File srcOutDir, String packageParent) throws Exception {
		Map<String, JavaType> originalToJavaTypes = getOriginalToJavaTypesMap(data);
		File parentDir = getParentSourceDir(srcOutDir, packageParent);
		for (JavaModule module : data.getModules()) {
			File moduleDir = new File(parentDir, module.getModuleName());
			if (!moduleDir.exists())
				moduleDir.mkdir();
			JavaImportHolder model = new JavaImportHolder(packageParent + "." + module.getModuleName());
			String clientClassName = Utils.capitalize(module.getModuleName()) + "Client";
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
			printModuleComment(module, classLines);
			classLines.addAll(Arrays.asList(
					"public class " + clientClassName + " {",
					"    private " + callerClass + " caller;",
					"",
					"    public " + clientClassName + "(String url) throws " + model.ref("java.net.MalformedURLException") + " {",
					"        caller = new " + callerClass + "(url);",
					"    }"
					));
			if (anyAuth) {
				classLines.addAll(Arrays.asList(
						"",
						"    public " + clientClassName + "(String url, String token) throws " + model.ref("java.net.MalformedURLException") + ", " + model.ref("java.io.IOException") + " {",
						"        caller = new " + callerClass + "(url, token);",
						"    }",
						"",
						"    public " + clientClassName + "(String url, String user, String password) throws " + model.ref("java.net.MalformedURLException") + " {",
						"        caller = new " + callerClass + "(url, user, password);",
						"    }"
						));
			}
			if (anyAuth) {
				classLines.addAll(Arrays.asList(
						"",
						"    public boolean isAuthAllowedForHttp() {",
						"        return caller.isAuthAllowedForHttp();",
						"    }",
						"",	
						"    public void setAuthAllowedForHttp(boolean isAuthAllowedForHttp) {",
						"        caller.setAuthAllowedForHttp(isAuthAllowedForHttp);",
						"    }"
						));
			}
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
				classLines.add("");
				printFuncComment(func, originalToJavaTypes, packageParent, classLines);
				classLines.add("    public " + retTypeName + " " + func.getJavaName() + "(" + funcParams + ") throws Exception {");
				classLines.add("        " + listClass + "<Object> args = new " + arrayListClass + "<Object>();");
				for (JavaFuncParam param : func.getParams()) {
					classLines.add("        args.add(" + param.getJavaName() + ");");
				}
				String typeReferenceClass = model.ref("org.codehaus.jackson.type.TypeReference");
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
					"package " + packageParent + "." + module.getModuleName() + ";",
					""
					));
			headerLines.addAll(model.generateImports());
			headerLines.add("");
			classLines.addAll(0, headerLines);
			Utils.writeFileLines(classLines, classFile);
		}
	}

	private static void printFuncComment(JavaFunc func, Map<String, JavaType> originalToJavaTypes, 
			String packageParent, List<String> classLines) {
		List<String> funcCommentLines = new ArrayList<String>();
		funcCommentLines.add("<p>Original spec-file function name: " + func.getOriginal().getName() + "</p>");
		funcCommentLines.add("<pre>");
		funcCommentLines.addAll(parseCommentLines(func.getOriginal().getComment()));
		funcCommentLines.add("</pre>");
		for (JavaFuncParam param : func.getParams()) {
			List<KbTypedef> refHistory = param.getType().getAliasHistoryOuterToDeep();
			if (refHistory.size() > 0) {
				String descr = createTypeDescr(originalToJavaTypes, refHistory, packageParent);
				funcCommentLines.add("@param   " + param.getJavaName() + "   " + descr);
			}
		}
		if (func.getReturns().size() > 0) {
			JavaType retType = func.getRetMultyType() == null ? func.getReturns().get(0).getType() : func.getRetMultyType();
			List<KbTypedef> refHistory =  retType.getAliasHistoryOuterToDeep();
			if (refHistory.size() > 0) {
				String descr = createTypeDescr(originalToJavaTypes, refHistory, packageParent);
				funcCommentLines.add("@return   " + descr);
			}
		}
		printCommentLines("    ", funcCommentLines, classLines);
	}

	private static String createTypeDescr(
			Map<String, JavaType> originalToJavaTypes,
			List<KbTypedef> refHistory, String packageParent) {
		StringBuilder sb = new StringBuilder();
		for (KbTypedef ref : refHistory) {
			if (sb.length() > 0)
				sb.append(" &rarr; ");
			sb.append("Original type \"").append(ref.getName()).append("\"");
			String originalTypeKey = Utils.capitalize(ref.getModule()).toLowerCase() + "." + ref.getName();
			if (originalToJavaTypes.containsKey(originalTypeKey)) {
				JavaType refJavaType = originalToJavaTypes.get(originalTypeKey);
				sb.append(" (see {@link ").append(getPackagePrefix(packageParent, refJavaType))
				.append(refJavaType.getJavaClassName()).append(' ').append(refJavaType.getJavaClassName())
				.append("} for details)");
			} else {
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
			}
		}
		String descr = sb.toString();
		return descr;
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
	
	private static String backupExtension() {
		String ret = ".bak-";
		SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd-HH-mm-ss");
		return ret + sdf.format(new Date());
	}
		
	private static void checkMatch(HashMap<String, String> code, Pattern matcher,
			String oldserver, String codekey, String errortype, boolean exceptOnFail) 
			throws ParseException {
		Matcher m = matcher.matcher(oldserver);
		if (!m.matches()) {
			if (exceptOnFail) {
				throw new ParseException("Missing " + errortype + 
						" in original file", 0);
			} else {
				return;
			}
		}
		code.put(codekey, m.group(1));
	}
	
	private static HashMap<String, String> parsePrevCode(File classFile, List<JavaFunc> funcs)
		throws IOException, ParseException {
		
		HashMap<String, String> code = new HashMap<String, String>();
		if (!classFile.exists()) {
			code.put(HEADER, "");
			code.put(CLSHEADER, "");
			code.put(CONSTRUCTOR, "");
			return code;
		}
		
		File backup = new File(classFile.getAbsoluteFile() + backupExtension());
		FileUtils.copyFile(classFile, backup);
		String oldserver = IOUtils.toString(new FileReader(classFile));
		checkMatch(code, PAT_HEADER, oldserver, HEADER, "header", true);
		checkMatch(code, PAT_CLASS_HEADER, oldserver, CLSHEADER, "class header", true);
		checkMatch(code, PAT_CONSTRUCTOR, oldserver, CONSTRUCTOR, "constructor", true);
		for (JavaFunc func: funcs) {
			String name = func.getOriginal().getName();
			Pattern p = Pattern.compile(MessageFormat.format(
					".*//BEGIN {0}\n(.*)        //END {0}\n.*", name), Pattern.DOTALL);
			checkMatch(code, p, oldserver, METHOD + name, "method " + name, false);
		}
		return code;
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
			File moduleDir = new File(parentDir, module.getModuleName());
			if (!moduleDir.exists())
				moduleDir.mkdir();
			JavaImportHolder model = new JavaImportHolder(packageParent + "." + module.getModuleName());
			String serverClassName = Utils.capitalize(module.getModuleName()) + "Server";
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
			classLines.addAll(splitCodeLines(originalCode.get(CLSHEADER)));
			classLines.add("    //END_CLASS_HEADER");
			classLines.add("");
			classLines.add("    public " + serverClassName + "() throws Exception {");
			classLines.add("        //BEGIN_CONSTRUCTOR");
			classLines.addAll(splitCodeLines(originalCode.get(CONSTRUCTOR)));
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
					funcParams.append(model.ref(utilPackage + ".auth.AuthUser")).append(" authPart");;					
				}
				String retTypeName = retType == null ? "void" : getJType(retType, packageParent, model);
				classLines.add("");
				printFuncComment(func, originalToJavaTypes, packageParent, classLines);
				classLines.add("    @" + model.ref(utilPackage + ".JsonServerMethod") + "(rpc = \"" + module.getOriginal().getModuleName() + "." + func.getOriginal().getName() + "\"" +
						(func.getRetMultyType() == null ? "" : ", tuple = true") + (func.isAuthOptional() ? ", authOptional=true" : "") + ")");
				classLines.add("    public " + retTypeName + " " + func.getJavaName() + "(" + funcParams + ") throws Exception {");
				
				List<String> funcLines = new LinkedList<String>();
				String name = func.getOriginal().getName();
				if (originalCode.containsKey(METHOD + name)) {
					funcLines.addAll(splitCodeLines(originalCode.get(METHOD + name)));
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
					"package " + packageParent + "." + module.getModuleName() + ";",
					""
					));
			headerLines.addAll(model.generateImports());
			headerLines.add("");
			headerLines.add("//BEGIN_HEADER");
			headerLines.addAll(splitCodeLines(originalCode.get(HEADER)));
			headerLines.add("//END_HEADER");
			headerLines.add("");
			classLines.addAll(0, headerLines);
			Utils.writeFileLines(classLines, classFile);
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
	
	private static void checkUtilityClasses(File srcOutDir, boolean createServers) throws Exception {
		checkUtilityClass(srcOutDir, "JsonClientCaller");
		checkUtilityClass(srcOutDir, "JacksonTupleModule");
		checkUtilityClass(srcOutDir, "UObject");
		if (createServers) {
			checkUtilityClass(srcOutDir, "JsonServerMethod");
			checkUtilityClass(srcOutDir, "JsonServerServlet");
		}
	}

	private static void checkUtilityClass(File srcOutDir, String className) throws Exception {
		File dir = new File(srcOutDir.getAbsolutePath() + "/" + utilPackage.replace('.', '/'));
		if (!dir.exists())
			dir.mkdirs();
		File dstClassFile = new File(dir, className + ".java");
		Utils.writeFileLines(Utils.readStreamLines(JavaTypeGenerator.class.getResourceAsStream(
				className + ".java.properties")), dstClassFile);
	}
	
	private static void checkLibs(File libOutDir, boolean createServers) throws Exception {
		if (libOutDir == null)
			return;
		if (!libOutDir.exists())
			libOutDir.mkdirs();
		checkLib(libOutDir, "jackson-all-1.9.11");
		checkLib(libOutDir, "kbase-auth");
		checkLib(libOutDir, "bcpkix-jdk15on-147");
		checkLib(libOutDir, "bcprov-ext-jdk15on-147");
		if (createServers) {
			checkLib(libOutDir, "servlet-api-2.5");
			checkLib(libOutDir, "jetty-all-7.0.0");
			checkLib(libOutDir, "ini4j-0.5.2");
		}
	}
	
	private static void checkLib(File libDir, String libName) throws Exception {
		String libFileName = libName + ".jar";
		InputStream is = JavaTypeGenerator.class.getResourceAsStream(libFileName + ".properties");
		OutputStream os = new FileOutputStream(new File(libDir, libFileName));
		Utils.copyStreams(is, os);
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
		if (type.getMainType() instanceof KbMapping) {
			JavaType firstInternal = type.getInternalTypes().get(0);
			if (!firstInternal.getJavaClassName().equals("String"))
				throw new IllegalStateException("Type [" + firstInternal.getOriginalTypeName() + "] " +
						"can not be used as map key type");
			JavaType subType = type.getInternalTypes().get(1);
			LinkedHashMap<String, Object> typeTree = createJsonRefTypeTree(type.getModuleName(), subType, 
					null, false, packageParent, tupleTypes);
			tree.put("additionalProperties", typeTree);
			throw new IllegalStateException();
		} else {
			LinkedHashMap<String, Object> props = new LinkedHashMap<String, Object>();
			for (int itemPos = 0; itemPos < type.getInternalTypes().size(); itemPos++) {
				JavaType iType = type.getInternalTypes().get(itemPos);
				String field = type.getInternalFields().get(itemPos);
				props.put(field, createJsonRefTypeTree(type.getModuleName(), iType, 
						type.getInternalComment(itemPos), false, packageParent, tupleTypes));
			}
			tree.put("properties", props);
			tree.put("additionalProperties", true);
		}
		ObjectMapper mapper = new ObjectMapper();
		mapper.configure(Feature.INDENT_OUTPUT, true);
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
					insideTypeParam, packageParent, tupleTypes);
			if (insideTypeParam) {
				typeTree.put("type", "object");
				typeTree.put("javaType", "java.util.List");
				typeTree.put("javaTypeParams", subType);
			} else {
				typeTree.put("type", "array");
				typeTree.put("items", subType);
			}
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
			typeTree.put("javaType", "java.lang.Object");
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
			return codeModel.ref(utilPackage + "." + "Tuple" + paramCount) + "<" + narrowParams + ">";
		} else if (kbt instanceof KbUnspecifiedObject) {
			return codeModel.ref("java.lang.Object");
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

		@Option(name="-p",usage="Java package parent (module subpackages are created in this package), default value is " + utilPackage, metaVar="<package>")		
		String packageParent = utilPackage;

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
