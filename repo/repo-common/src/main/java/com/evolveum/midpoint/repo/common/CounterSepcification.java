/*
 * Copyright (c) 2010-2018 Evolveum
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.evolveum.midpoint.repo.common;

import java.time.Duration;

import com.evolveum.midpoint.prism.xml.XmlTypeConverter;
import com.evolveum.midpoint.util.DebugDumpable;
import com.evolveum.midpoint.xml.ns._public.common.common_3.PolicyThresholdType;

/**
 * @author katka
 *
 */
public class CounterSepcification implements DebugDumpable {
	
	private int count = 0;
	private long counterStart;
	private PolicyThresholdType policyThreshold; 
	
	public int getCount() {
		return count;
	}
	public long getCounterStart() {
		return counterStart;
	}
	public void setCount(int count) {
		this.count = count;
	}
	public void setCounterStart(long counterStart) {
		this.counterStart = counterStart;
	}

	/**
	 * @return the policyThreshold
	 */
	public PolicyThresholdType getPolicyThreshold() {
		return policyThreshold;
	}
	
	/**
	 * @param policyThreshold the policyThreshold to set
	 */
	public void setPolicyThreshold(PolicyThresholdType policyThreshold) {
		this.policyThreshold = policyThreshold;
	}
	
	public void reset(long currentTimeMillis) {
		count = 0;
		counterStart = currentTimeMillis;
	}
	
	@Override
	public String debugDump(int indent) {
		StringBuilder sb = new StringBuilder();
		sb.append("Current count: ").append(count).append("\n");
		sb.append("Counter start: ").append(XmlTypeConverter.createXMLGregorianCalendar(counterStart)).append("\n");
		sb.append("Thresholds: \n").append(policyThreshold.toString());
		return sb.toString();
	}
	
}
