/*
 * Sifarish: Recommendation Engine
 * Author: Pranab Ghosh
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you
 * may not use this file except in compliance with the License. You may
 * obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0 
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package org.sifarish.common;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.conf.Configured;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.NullWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.Mapper;
import org.apache.hadoop.mapreduce.Reducer;
import org.apache.hadoop.mapreduce.Mapper.Context;
import org.apache.hadoop.mapreduce.lib.input.FileInputFormat;
import org.apache.hadoop.mapreduce.lib.input.FileSplit;
import org.apache.hadoop.mapreduce.lib.output.FileOutputFormat;
import org.apache.hadoop.util.Tool;
import org.chombo.util.SecondarySort;
import org.chombo.util.Tuple;
import org.chombo.util.Utility;

public class AttributeBasedDiversifier   extends Configured implements Tool{
    @Override
    public int run(String[] args) throws Exception   {
        Job job = new Job(getConf());
        String jobName = "Attribute based diversifer for ranked and  recommended items  MR";
        job.setJobName(jobName);
        
        job.setJarByClass(AttributeBasedDiversifier.class);
        
        FileInputFormat.addInputPath(job, new Path(args[0]));
        FileOutputFormat.setOutputPath(job, new Path(args[1]));

        job.setMapperClass(ItemRatingAttributeAggregator.ItemAggregatorMapper.class);
        job.setReducerClass(ItemRatingAttributeAggregator.ItemAggregatorReducer.class);
        
        job.setMapOutputKeyClass(Tuple.class);
        job.setMapOutputValueClass(Tuple.class);

        job.setOutputKeyClass(NullWritable.class);
        job.setOutputValueClass(Text.class);
 
        job.setGroupingComparatorClass(SecondarySort.TuplePairGroupComprator.class);
        job.setPartitionerClass(SecondarySort.TuplePairPartitioner.class);

        Utility.setConfiguration(job.getConfiguration());
        job.setNumReduceTasks(job.getConfiguration().getInt("num.reducer", 1));
        int status =  job.waitForCompletion(true) ? 0 : 1;
        return status;
    }

    /**
     * @author pranab
     *
     */
    public static class AttributeDiversifierMapper extends Mapper<LongWritable, Text, Tuple, Tuple> {
    	private String fieldDelimRegex;
    	private Tuple keyOut = new Tuple();
    	private Tuple valOut = new Tuple();
    	private boolean isMetaDataFileSplit;
    	private String userD;
    	private int[] attrOrdinals;
    	
        /* (non-Javadoc)
         * @see org.apache.hadoop.mapreduce.Mapper#setup(org.apache.hadoop.mapreduce.Mapper.Context)
         */
        protected void setup(Context context) throws IOException, InterruptedException {
        	Configuration config = context.getConfiguration();
        	fieldDelimRegex = config.get("field.delim.regex", ",");
        	String metaDataFilePrefix = config.get("item.metadta.file.prefix", "meta");
        	isMetaDataFileSplit = ((FileSplit)context.getInputSplit()).getPath().getName().startsWith(metaDataFilePrefix);
        	attrOrdinals = Utility.intArrayFromString(config.get("attr.ordinals"));
        }

        /* (non-Javadoc)
         * @see org.apache.hadoop.mapreduce.Mapper#map(KEYIN, VALUEIN, org.apache.hadoop.mapreduce.Mapper.Context)
         */
        @Override
        protected void map(LongWritable key, Text value, Context context)
            throws IOException, InterruptedException {
           	String[] items = value.toString().split(fieldDelimRegex);
           	keyOut.initialize();
           	valOut.initialize();
      		userD = items[0];
          	if (isMetaDataFileSplit) {
           		//items attributes
            	keyOut.add(userD, 1);
           		valOut.append(1);
           		for (int i = 1;  i < items.length;  ++i) {
           			valOut.append(items[i]);
           		}
           		
           	} else {
           		//predicted rating
           		keyOut.add(userD, 0);
           		valOut.add(0,items[1], Integer.parseInt(items[2]));
           	}
           	context.write(keyOut, valOut);

        }

    }

    /**
     * @author pranab
     *
     */
    public static class AttributeDiversifierReducer extends Reducer<Tuple, Tuple, NullWritable, Text> {
    	private String fieldDelim;
    	private Text valOut = new Text();
    	private int minRankDistance;
    	private String userID;
    	private String itemID;
    	private int rating;
		private StringBuilder stBld =  new StringBuilder();
		private Map<String, RatedItem> ratedItems = new HashMap<String, RatedItem>();
		private Map<String, List<RatedItem>> attrPartitionedRatedItems = new HashMap<String, List<RatedItem>>();
		private List<RatedItem> ratedItemList;
		private List<RatedItem> reorderedRatedItemList = new ArrayList<RatedItem>();
		private List<RatedItemWithAttributes> topRatedWithDiffAttributes = new ArrayList<RatedItemWithAttributes>();
		private Map<String, Integer> itemRankIndex = new HashMap<String, Integer>();
		
		
    	/* (non-Javadoc)
         * @see org.apache.hadoop.mapreduce.Reducer#setup(org.apache.hadoop.mapreduce.Reducer.Context)
         */
        protected void setup(Context context) throws IOException, InterruptedException {
        	Configuration config = context.getConfiguration();
        	fieldDelim = config.get("field.delim", ",");
        	minRankDistance = config.getInt("min.rank.distance",  5);
        }
        
        /* (non-Javadoc)
         * @see org.apache.hadoop.mapreduce.Reducer#reduce(KEYIN, java.lang.Iterable, org.apache.hadoop.mapreduce.Reducer.Context)
         */
        protected void reduce(Tuple  key, Iterable<Tuple> values, Context context)
        throws IOException, InterruptedException {
        	userID = key.getString(0);
        	ratedItems.clear();
        	attrPartitionedRatedItems.clear();
        	itemRankIndex.clear();
        	reorderedRatedItemList.clear();
        	
        	for(Tuple value : values) {
        		int type = value.getInt(0);
        		if (0 == type) {
        			//predicted ratings
        			itemID = value.getString(1);
        			rating = value.getInt(2);
        			ratedItems.put(itemID, new RatedItem(itemID, rating));
        		} else {
        			//item attributes
        			itemID = value.getString(1);
        			String attrs = value.toString(2);
        			ratedItemList = attrPartitionedRatedItems.get(attrs);
        			if (null == ratedItems) {
        				ratedItemList = new ArrayList<RatedItem>();
        				attrPartitionedRatedItems.put(attrs, ratedItemList);
        			}
        			ratedItemList.add(ratedItems.get(itemID));
        		}
        		
        		//reorder
        		reorderByRankDiatance();
        		
        		//emit
        		for (RatedItem ratedItem :   reorderedRatedItemList) {
               		stBld.delete(0, stBld.length());
               		stBld.append(userID).append(fieldDelim).append(ratedItem.getLeft()).append(fieldDelim).
               			append(ratedItem.getRight());
               		valOut.set(stBld.toString());
               		context.write(NullWritable.get(), valOut);
        		}
        	}
        }    
        
        /**
         * reorders items taking min rank distance into consideration
         */
        private void reorderByRankDiatance() {
        	//sort rated items for each attribute set value
        	for (String attrs : attrPartitionedRatedItems.keySet())  {
    			ratedItemList = attrPartitionedRatedItems.get(attrs);
        		Collections.sort(ratedItemList);
        	}
        	
        	//build reordered list
        	boolean done = false;
        	while (!done) {
	            	topRatedWithDiffAttributes.clear();
	            	
	            	//collect top rated items for each unique attribute
	               	for (String attrs : attrPartitionedRatedItems.keySet())  {
	        			ratedItemList = attrPartitionedRatedItems.get(attrs);
	        			if (!ratedItemList.isEmpty()) {
	        				RatedItem ratedItem = ratedItemList.get(0);
	        				topRatedWithDiffAttributes.add(new RatedItemWithAttributes(ratedItem.getLeft(), ratedItem.getRight(), attrs));
	        			}
	               	}
	               
	               	if (!topRatedWithDiffAttributes.isEmpty()) {
	               		//sort by rating
		               	Collections.sort(topRatedWithDiffAttributes);
		               	
		               	//pick top rated with rank distance above minimum
		               	RatedItemWithAttributes selectedRateItemAttr = null; 
		               	int maxRankDist = 0;
		               	RatedItemWithAttributes rateItemAttrWithMaxRankDist = null;
		               	for (RatedItemWithAttributes rateItemAttr :  topRatedWithDiffAttributes) {
		               		Integer index = itemRankIndex.get(rateItemAttr.getAttributes());
		               		if (null == index) {
		               			//no item with same attribute yet
		               			selectedRateItemAttr = rateItemAttr;
		               			break;
		               		} else {
		               			int rankDist = reorderedRatedItemList.size() - index;
		               			
		               			//keep tract of item with max rank distance
		               			if (rankDist > maxRankDist) {
		               				maxRankDist = rankDist;
		               				rateItemAttrWithMaxRankDist = rateItemAttr;
		               			}
		               			
		               			if (rankDist >= minRankDistance) {
		               				//min rank distance requirement  met
			               			selectedRateItemAttr = rateItemAttr;
			               			break;
		               			}
		               		}
		               	}
		               	
		               	//if item with greater than min rank distance not found
		               	if (null == selectedRateItemAttr) {
		               		//use the item with max rank distance
		               		selectedRateItemAttr = rateItemAttrWithMaxRankDist;
		               	}
		               	
	               		//add to list and update index
	               		String attrs = selectedRateItemAttr.getAttributes();
	               		itemRankIndex.put(attrs, reorderedRatedItemList.size());
	               		RatedItem ratedItem = attrPartitionedRatedItems.get(attrs).remove(0);
	               		reorderedRatedItemList.add(ratedItem);
	               	} else {
	               		done = true;
	               	}
        		}
        }
        
    }
}
