package gov.doe.kbase.scripts;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public class KbTuple extends KbBasicType {
	private List<String> elementNames = null;
	private List<KbType> elementTypes = null;
	
	public KbTuple() {}
	
	public KbTuple(List<KbType> types) {
		elementNames = new ArrayList<String>();
		for (KbType type : types)
			elementNames.add(null);
		elementNames = Collections.unmodifiableList(elementNames);
		elementTypes = Collections.unmodifiableList(types);
	}
	
	public KbTuple loadFromMap(Map<?,?> data, JSyncProcessor subst) {
		List<?> optionList = Utils.propList(data, "element_names");
		elementNames = Collections.unmodifiableList(Utils.repareTypingString(optionList));
		elementTypes = new ArrayList<KbType>();
		for (Map<?,?> itemProps : Utils.getListOfMapProp(data, "element_types", subst)) {
			elementTypes.add(Utils.createTypeFromMap(itemProps, subst));
		}
		elementTypes = Collections.unmodifiableList(elementTypes);
		return this;
	}
		
	public List<String> getElementNames() {
		return elementNames;
	}
	
	public List<KbType> getElementTypes() {
		return elementTypes;
	}
	
	@Override
	public String getJavaStyleName() {
		return "Tuple";
	}
}