/*
 * Copyright 2012 LinkedIn Corp.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package azkaban.viewer.pigvisualizer;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import org.apache.hadoop.mapred.RunningJob;
import org.apache.hadoop.mapred.TIPStatus;
import org.apache.hadoop.mapred.TaskReport;

/**
 * Container that holds state of a MapReduce job
 */
public class MapReduceJobState {
	private String jobId;
	private String jobName;
	private String trackingURL;
	private boolean isComplete;
	private boolean isSuccessful;
	private float mapProgress;
	private float reduceProgress;
	private long jobStartTime;
	private long jobLastUpdateTime;

	private int totalMappers;
	private int finishedMappersCount;

	private int totalReducers;
	private int finishedReducersCount;

	public MapReduceJobState() {
	}

	@SuppressWarnings("deprecation")
	public MapReduceJobState(RunningJob runningJob,
			TaskReport[] mapTaskReport,
			TaskReport[] reduceTaskReport) throws IOException {
		jobId = runningJob.getID().toString();
		jobName = runningJob.getJobName();
		trackingURL = runningJob.getTrackingURL();
		isComplete = runningJob.isComplete();
		isSuccessful = runningJob.isSuccessful();
		mapProgress = runningJob.mapProgress();
		reduceProgress = runningJob.reduceProgress();

		totalMappers = mapTaskReport.length;
		totalReducers = reduceTaskReport.length;

		for (TaskReport report : mapTaskReport) {
			if (report.getStartTime() < jobStartTime || jobStartTime == 0L) {
				jobStartTime = report.getStartTime();
			}

			TIPStatus status = report.getCurrentStatus();
			if (status != TIPStatus.PENDING && status != TIPStatus.RUNNING) {
				finishedMappersCount++;
			}
		}

		for (TaskReport report : reduceTaskReport) {
			if (jobLastUpdateTime < report.getFinishTime()) { 
				jobLastUpdateTime = report.getFinishTime();
			}

			TIPStatus status = report.getCurrentStatus();
			if (status != TIPStatus.PENDING && status != TIPStatus.RUNNING) {
				finishedReducersCount++;
			}
		}

		// If not all the reducers are finished.
		if (finishedReducersCount != reduceTaskReport.length || 
				jobLastUpdateTime == 0) {
			jobLastUpdateTime = System.currentTimeMillis();
		}
	}

	public String getJobId() {
		return jobId;
	}

	public void setJobId(String jobId) {
		this.jobId = jobId;
	}

	public String getJobName() {
		return jobName;
	}

	public void setJobName(String jobName) {
		this.jobName = jobName;
	}

	public String getTrackingURL() {
		return trackingURL;
	}

	public void setTrackingURL(String trackingURL) {
		this.trackingURL = trackingURL;
	}

	public boolean isComplete() {
		return isComplete;
	}

	public void setComplete(boolean complete) {
		isComplete = complete;
	}

	public boolean isSuccessful() {
		return isSuccessful;
	}

	public void setSuccessful(boolean successful) {
		isSuccessful = successful;
	}

	public float getMapProgress() {
		return mapProgress;
	}

	public void setMapProgress(float mapProgress) {
		this.mapProgress = mapProgress;
	}

	public float getReduceProgress() {
		return reduceProgress;
	}

	public void setReduceProgress(float reduceProgress) {
		this.reduceProgress = reduceProgress;
	}

	public int getTotalMappers() {
		return totalMappers;
	}

	public void setTotalMappers(int totalMappers) {
		this.totalMappers = totalMappers;
	}

	public int getTotalReducers() {
		return totalReducers;
	}

	public void setTotalReducers(int totalReducers) {
		this.totalReducers = totalReducers;
	}

	public int getFinishedMappersCount() {
		return finishedMappersCount;
	}

	public void setFinishedMappersCount(int finishedMappersCount) {
		this.finishedMappersCount = finishedMappersCount;
	}

	public int getFinishedReducersCount() {
		return finishedReducersCount;
	}

	public void setFinishedReducersCount(int finishedReducersCount) {
		this.finishedReducersCount = finishedReducersCount;
	}

	public long getJobStartTime() {
		return jobStartTime;
	}

	public void setJobStartTime(long jobStartTime) {
		this.jobStartTime = jobStartTime;
	}

	public long getJobLastUpdateTime() {
		return jobLastUpdateTime;
	}

	public void setJobLastUpdateTime(long jobLastUpdateTime) {
		this.jobLastUpdateTime = jobLastUpdateTime;
	}

	public Object toJson() {
		Map<String, Object> jsonObj = new HashMap<String, Object>();
		jsonObj.put("trackingURL", trackingURL);
		jsonObj.put("isComplete", String.valueOf(isComplete));
		jsonObj.put("isSuccessful", String.valueOf(isSuccessful));
		return jsonObj;
	}

	@SuppressWarnings("unchecked")
	public static MapReduceJobState fromJson(Object obj) throws Exception {
		Map<String, Object> jsonObj = (HashMap<String, Object>) obj;
		MapReduceJobState state = new MapReduceJobState();
		state.setTrackingURL((String) jsonObj.get("trackingURL"));
		state.setComplete(
				Boolean.parseBoolean((String) jsonObj.get("isComplete")));
		state.setSuccessful(
				Boolean.parseBoolean((String) jsonObj.get("isSuccessful")));
		return state;
	}
}