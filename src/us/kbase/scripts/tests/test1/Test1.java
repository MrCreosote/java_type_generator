package us.kbase.scripts.tests.test1;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import us.kbase.Tuple2;
import us.kbase.Tuple4;

import junit.framework.Assert;

import us.kbase.scripts.tests.test1.basic.BasicClient;
import us.kbase.scripts.tests.test1.basic.ComplexStruct;
import us.kbase.scripts.tests.test1.basic.SimpleStruct;

public class Test1 {
	public Test1(BasicClient client) throws Exception {
		Integer val1 = 12345;
		Assert.assertEquals(val1, client.oneSimpleParam(val1));
		Double val2 = 12.345012345;
		String val3 = "12-345";
		Map<String, Integer> map1 = new LinkedHashMap<String, Integer>();
		String key1 = "123";
		map1.put(key1, val1);
		Map<String, List<Double>> map2 = new LinkedHashMap<String, List<Double>>();
		String key2 = "678";
		map2.put(key2, Arrays.asList(val2));
		Tuple2<List<Map<String, Integer>>, Map<String, List<Double>>> val4 = 
				new Tuple2<List<Map<String, Integer>>, Map<String, List<Double>>>().withE1(Arrays.asList(map1)).withE2(map2);
		Tuple4<Integer, Double, String, Tuple2<List<Map<String, Integer>>, Map<String, List<Double>>>> ret = 
				client.manySimpleParams(val1, val2, val3, val4);
		Assert.assertEquals(val1, ret.getE1());
		Assert.assertEquals(val2, ret.getE2());
		Assert.assertEquals(val3, (String)ret.getE3());
		Assert.assertEquals(1, ret.getE4().getE1().size());
		Assert.assertEquals(val1, ret.getE4().getE1().get(0).get(key1));
		Assert.assertEquals(1, ret.getE4().getE2().get("678").size());
		Assert.assertEquals(val2, ret.getE4().getE2().get("678").get(0));
		SimpleStruct simpleStruct = new SimpleStruct().withProp1(val1).withProp2(val2).withProp3(val3);
		Map<String, SimpleStruct> largeMap1 = new LinkedHashMap<String, SimpleStruct>();
		largeMap1.put(key1, simpleStruct);
		Map<String, List<SimpleStruct>> largeMap2 = new LinkedHashMap<String, List<SimpleStruct>>();
		largeMap2.put(key2, Arrays.asList(simpleStruct));
		Map<String, List<Map<String, SimpleStruct>>> map3 = new LinkedHashMap<String, List<Map<String, SimpleStruct>>>();
		String key3 = "999999";
		map3.put(key3, Arrays.asList(largeMap1));
		ComplexStruct complexStruct = new ComplexStruct().withLargeProp1(Arrays.asList(simpleStruct))
				.withLargeProp2(largeMap1).withLargeProp3(Arrays.asList(largeMap2)).withLargeProp4(map3);
		ComplexStruct retStruct = client.oneComplexParam(complexStruct);
		checkComplexStruct(simpleStruct, key1, key2, key3, retStruct);
		Tuple2<SimpleStruct, ComplexStruct> ret2 = client.manyComplexParams(simpleStruct, complexStruct);
		checkSimpleStruct(simpleStruct, ret2.getE1());
		checkComplexStruct(simpleStruct, key1, key2, key3, ret2.getE2());
	}

	private void checkComplexStruct(SimpleStruct expectedSimpleStruct, String key1, String key2,
			String key3, ComplexStruct actualComplexStruct) {
		checkSimpleStruct(expectedSimpleStruct, actualComplexStruct.getLargeProp1().get(0));
		checkSimpleStruct(expectedSimpleStruct, actualComplexStruct.getLargeProp2().get(key1));
		checkSimpleStruct(expectedSimpleStruct, actualComplexStruct.getLargeProp3().get(0).get(key2).get(0));
		checkSimpleStruct(expectedSimpleStruct, actualComplexStruct.getLargeProp4().get(key3).get(0).get(key1));
	}

	private static void checkSimpleStruct(SimpleStruct expected, SimpleStruct actual) {
		Assert.assertEquals(expected.getProp1(), actual.getProp1());
		Assert.assertEquals(expected.getProp2(), actual.getProp2());
		Assert.assertEquals(expected.getProp3(), actual.getProp3());
	}
}
