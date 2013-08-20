package us.kbase.scripts.tests.test2;

import us.kbase.scripts.tests.test2.annotation.Gene;
import us.kbase.scripts.tests.test2.annotation.Genome;
import us.kbase.scripts.tests.test2.regulation.BindingSite;
import us.kbase.scripts.tests.test2.regulation.RegulationClient;
import us.kbase.scripts.tests.test2.sequence.SequencePos;
import us.kbase.scripts.tests.test2.taxonomy.Taxon;

import java.util.Arrays;
import java.util.List;

import us.kbase.Tuple2;
import us.kbase.Tuple3;

import junit.framework.Assert;

public class Test2 {
	public Test2(RegulationClient client) throws Exception {
		Taxon taxon = new Taxon().withId(123).withName("Superbacteria").withSubTaxons(Arrays.asList(456));
		Tuple2<String, Genome> ret1 = client.getGenome("test", new Genome().withTaxon(taxon));
		Assert.assertEquals(taxon.getId(), ret1.getE2().getTaxon().getId());
		SequencePos pos1 = new SequencePos().withStart(1).withStop(1111).withSequenceId(33);
		Gene selfReg = new Gene().withGeneId(123).withPos(pos1);
		BindingSite bs = new BindingSite().withBindingPos(pos1).withRegulator(selfReg);
		Tuple3<Gene, List<BindingSite>, List<Gene>> ret2 = client.getRegulatorBindingSitesAndGenes(
				selfReg, Arrays.asList(bs), Arrays.asList(selfReg));
		Assert.assertEquals(pos1.getStop(), ret2.getE1().getPos().getStop());
		Assert.assertEquals(selfReg.getGeneId(), ret2.getE2().get(0).getRegulator().getGeneId());
	}
}
