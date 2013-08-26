package us.kbase.kidl;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public class KbFuncdef implements KbModuleComp {
	private String name;
	private boolean async;
	private String authentication;
	private String comment;
	private List<KbParameter> parameters;
	private List<KbParameter> returnType;
	private Map<?,?> data = null;
	
	public KbFuncdef loadFromMap(Map<?,?> data) {
		name = Utils.prop(data, "name");
		async = (0 != Utils.intPropFromString(data, "async"));
		authentication = Utils.prop(data, "authentication");
		comment = Utils.prop(data, "comment");
		parameters = loadParameters(Utils.propList(data, "parameters"), false);
		returnType = loadParameters(Utils.propList(data, "return_type"), true);
		this.data = data;
		return this;
	}
	
	private static List<KbParameter> loadParameters(List<?> inputList, boolean isReturn) {
		List<KbParameter> ret = new ArrayList<KbParameter>();
		for (Map<?,?> data : Utils.repareTypingMap(inputList)) {
			ret.add(new KbParameter().loadFromMap(data, isReturn, ret.size() + 1));
		}
		return Collections.unmodifiableList(ret);
	}
	
	public String getName() {
		return name;
	}
	
	public boolean isAsync() {
		return async;
	}
	
	public String getAuthentication() {
		return authentication;
	}
	
	public String getComment() {
		return comment;
	}
	
	public List<KbParameter> getParameters() {
		return parameters;
	}
	
	public List<KbParameter> getReturnType() {
		return returnType;
	}
	
	public Map<?, ?> getData() {
		return data;
	}
}
