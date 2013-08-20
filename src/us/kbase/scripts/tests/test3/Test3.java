package us.kbase.scripts.tests.test3;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import junit.framework.Assert;

import us.kbase.Tuple2;
import us.kbase.Tuple3;
import us.kbase.scripts.tests.test3.tuplestest.TuplestestClient;

public class Test3 {
	
	public Test3(TuplestestClient client) throws Exception {
		Tuple3<Integer, Double, String> tuple = new Tuple3<Integer, Double, String>();
		tuple.setE1(123);
		tuple.setE2(56.789);
		tuple.setE3("zero");
		String key = "key";
		Map<String, Tuple3<Integer, Double, String>> map1 = new LinkedHashMap<String, Tuple3<Integer, Double, String>>();
		map1.put(key, tuple);
		Map<String,List<Tuple3<Integer, Double, String>>> map2 = new LinkedHashMap<String,List<Tuple3<Integer, Double, String>>>();
		map2.put(key, Arrays.asList(tuple));
		Tuple2<List<Map<String,Tuple3<Integer, Double, String>>>, Map<String,List<Tuple3<Integer, Double, String>>>> input1 =
				new Tuple2<List<Map<String,Tuple3<Integer, Double, String>>>, Map<String,List<Tuple3<Integer, Double, String>>>>();
		input1.setE1(Arrays.asList(map1));
		input1.setE2(map2);
		Tuple2<List<Map<String,Tuple3<Integer, Double, String>>>, Map<String,List<Tuple3<Integer, Double, String>>>> ret1 =
				client.simpleCall(input1);
		checkTuple(tuple, ret1.getE1().get(0).get(key));
		checkTuple(tuple, ret1.getE2().get(key).get(0));
		Tuple2<List<Map<String,Tuple3<Integer, Double, String>>>, Map<String,List<Tuple3<Integer, Double, String>>>> ret2 =
				client.complexCall(Arrays.asList(map1), map2);
		checkTuple(tuple, ret2.getE1().get(0).get(key));
		checkTuple(tuple, ret2.getE2().get(key).get(0));
	}
	
	private static void checkTuple(Tuple3<Integer, Double, String> expected, Tuple3<Integer, Double, String> actual) {
		Assert.assertEquals(expected.getE1(), actual.getE1());
		Assert.assertEquals(expected.getE2(), actual.getE2());
		Assert.assertEquals(expected.getE3(), actual.getE3());
	}
	
	public static void main(String[] args) throws Exception {
		new Test3(new TuplestestClient("http://localhost:9999"));
	}
}