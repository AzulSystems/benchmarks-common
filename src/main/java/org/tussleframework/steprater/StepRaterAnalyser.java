/*
 * Copyright (c) 2021-2022, Azul Systems
 * 
 * All rights reserved.
 * 
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 * 
 * * Redistributions of source code must retain the above copyright notice, this
 *   list of conditions and the following disclaimer.
 * 
 * * Redistributions in binary form must reproduce the above copyright notice,
 *   this list of conditions and the following disclaimer in the documentation
 *   and/or other materials provided with the distribution.
 * 
 * * Neither the name of [project] nor the names of its
 *   contributors may be used to endorse or promote products derived from
 *   this software without specific prior written permission.
 * 
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
 * FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
 * CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
 * OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 * 
 */

package org.tussleframework.steprater;

import static org.tussleframework.HdrIntervalResult.reportedType;
import static org.tussleframework.HdrIntervalResult.reportedTypeCount;
import static org.tussleframework.WithException.withException;
import static org.tussleframework.WithException.wrapException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.stream.DoubleStream;

import org.tussleframework.HdrResult;
import org.tussleframework.metrics.Marker;
import org.tussleframework.metrics.Metric;
import org.tussleframework.metrics.MetricType;
import org.tussleframework.metrics.MetricValue;
import org.tussleframework.metrics.MovingWindowSLE;
import org.tussleframework.tools.Analyzer;
import org.tussleframework.tools.AnalyzerConfig;
import org.tussleframework.tools.ConfigLoader;
import org.tussleframework.tools.FormatTool;
import org.tussleframework.tools.LoggerTool;

public class StepRaterAnalyser extends Analyzer {

    public static void main(String[] args) {
        LoggerTool.init("tussle-analyser");
        try {
            new StepRaterAnalyser().processResults(args);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public AnalyzerConfig loadConfig(String[] args) throws IOException, ReflectiveOperationException {
        return ConfigLoader.loadObject(args, StepRaterConfig.class);
    }

    @Override
    public void processResults(String[] args) throws Exception {
        init(args);
        processRecursive();
        processSummary();
        printResults();
    }

    public void processResults(StepRaterConfig config, List<HdrResult> results) throws Exception {
        init(config);
        withException(() -> results.forEach(result -> wrapException(() -> processResultHistograms(result))));
        processSummary();
        printResults();
    }

    private HashMap<String, ArrayList<HdrResult>> getResultsMap() {
        HashMap<String, ArrayList<HdrResult>> resultsMap = new HashMap<>();
        for (HdrResult result : hdrResults) {
            String name = result.operationName + " " + result.metricName;
            ArrayList<HdrResult> specificResuls;
            if (resultsMap.containsKey(name)) {
                specificResuls = resultsMap.get(name);
            } else {
                specificResuls = new ArrayList<>();
                resultsMap.put(name, specificResuls);
            }
            specificResuls.add(result);
        }
        resultsMap.forEach((metricName, specificResuls) -> specificResuls.sort((a, b) -> (int) (a.targetRate - b.targetRate)));
        return resultsMap;
    }

    public void processSummary() {
        MovingWindowSLE[] sleConfig = analyzerConfig.sleConfig;
        // split results by operation and metric names: reads response_time, reads service_time, writes response_time, etc.
        HashMap<String, ArrayList<HdrResult>> resultsMap = getResultsMap();
        // process each metric group separately
        resultsMap.forEach((opAndMetricName, specificResults) -> {
            ArrayList<MovingWindowSLE> unprocessedSlaConfig = new ArrayList<>();
            Collections.addAll(unprocessedSlaConfig, sleConfig);
            ArrayList<Marker> slaMarkers = new ArrayList<>();
            HashMap<String, Double> slaBroken = new HashMap<>();
            for (HdrResult result : specificResults) {
                Iterator<MovingWindowSLE> iterator = unprocessedSlaConfig.iterator();
                while (iterator.hasNext()) {
                    MovingWindowSLE sla = iterator.next();
                    if (!result.checkSLE(sla)) {
                        log(opAndMetricName + " SLA for " + sla + " broken on " + result.targetRate + " msgs/s");
                        iterator.remove();
                        slaBroken.put(sla.longName(), result.targetRate);
                        slaMarkers.add(new Marker(sla.nameWithMax(), result.targetRate, sla.maxValue));
                    }
                }
            }
            for (int i = 0; i < unprocessedSlaConfig.size(); i++) {
                MovingWindowSLE sla = unprocessedSlaConfig.get(i);
                log(opAndMetricName + " SLA for " + sla + " was not broken");
            }
            DoubleStream.Builder[] valBuffersHdr = new DoubleStream.Builder[reportedTypeCount()];
            DoubleStream.Builder[] valBuffersMax = new DoubleStream.Builder[reportedTypeCount()];
            DoubleStream.Builder[] valBuffersAvg = new DoubleStream.Builder[reportedTypeCount()];
            for (int i = 0; i < reportedTypeCount(); i++) {
                valBuffersHdr[i] = DoubleStream.builder();
                valBuffersMax[i] = DoubleStream.builder();
                valBuffersAvg[i] = DoubleStream.builder();
            }
            DoubleStream.Builder[] valBuffersMW = new DoubleStream.Builder[sleConfig.length];
            for (int j = 0; j < sleConfig.length; j++) {
                valBuffersMW[j] = DoubleStream.builder();
            }
            ArrayList<String> xValuesBuff = new ArrayList<>();
            int empyCount = 0;
            for (HdrResult result : specificResults) {
                xValuesBuff.add(FormatTool.format(result.targetRate));
                if (0 == result.recordsCount) {
                    empyCount++;
                    continue;
                }
                for (int j = 0; j < reportedTypeCount(); j++) {
                    Metric metric = result.subIntervalHistograms.get(0).getMetric();
                    if (metric == null) {
                        continue;
                    }
                    MetricValue mv = metric.byType(reportedType(j));
                    if (mv == null) {
                        valBuffersMax[j].add(-1.0);
                        valBuffersAvg[j].add(-1.0);
                    } else if (reportedType(j) == MetricType.COUNTS) {
                        valBuffersMax[j].add(mv.sumValue());
                        valBuffersAvg[j].add(mv.sumValue());
                    } else {
                        valBuffersMax[j].add(mv.maxValue());
                        valBuffersAvg[j].add(mv.avgValue());
                    }
                }
            }
            log("findConformingRate - empyCount=" + empyCount);
            if (empyCount == specificResults.size()) {
                return;
            }
            HdrResult specificResult = specificResults.stream().filter(result -> result.recordsCount > 0).findFirst().get();
            String[] xValues = xValuesBuff.toArray(EMPT);
            Metric mHdr = Metric.builder()
                    .name(specificResult.metricName + " summary_hdr")
                    .operation(specificResult.operationName)
                    .units("ms")
                    .xunits(specificResult.rateUnits)
                    .xValues(xValues)
                    .build();
            Metric mMax = Metric.builder()
                    .name(specificResult.metricName + " summary_max")
                    .operation(specificResult.operationName)
                    .units("ms")
                    .xunits(specificResult.rateUnits)
                    .xValues(xValues)
                    .build();
            Metric mAvg = Metric.builder()
                    .name(specificResult.metricName + " summary_avg" )
                    .operation(specificResult.operationName)
                    .units("ms")
                    .xunits(specificResult.rateUnits)
                    .xValues(xValues).build();
            for (int i = 0; i < reportedTypeCount(); i++) {
                mHdr.add(new MetricValue(reportedType(i).name(), valBuffersHdr[i].build().toArray()));
                mMax.add(new MetricValue(reportedType(i).name(), valBuffersMax[i].build().toArray()));
                mAvg.add(new MetricValue(reportedType(i).name(), valBuffersAvg[i].build().toArray()));
            }
            metricData.add(mHdr);
            metricData.add(mMax);
            metricData.add(mAvg);
            double maxRate = specificResults.get(specificResults.size() - 1).targetRate;
            String[] slaTypes = getTypes(sleConfig);
            for (int i = 0; i < sleConfig.length; i++) {
                MovingWindowSLE sla = sleConfig[i];
                String slaName = sla.longName();
                String oName = specificResult.operationName + " " + slaName;
                String mName = specificResult.metricName;
                if (slaBroken.containsKey(slaName)) {
                    metricData.add(Metric.builder()
                            .name(mName + " conforming_rate")
                            .operation(oName)
                            .units(specificResult.rateUnits)
                            .value(slaBroken.get(slaName))
                            .build());
                } else {
                    metricData.add(Metric.builder()
                            .name(mName + " conforming_rate (unbroken)")
                            .operation(oName)
                            .units(specificResult.rateUnits)
                            .value(maxRate)
                            .build());
                }
                metricData.add(Metric.builder()
                        .name(mName + " summary_max")
                        .operation(oName)
                        .units("ms")
                        .xunits(specificResult.rateUnits)
                        .xValues(xValues)
                        .group(opAndMetricName + " mw_summary_max")
                        .build()
                        .add(new MetricValue(slaTypes[i], valBuffersMW[i].build().toArray())));
            }
            mHdr.setMarkers(slaMarkers);
            mMax.setMarkers(slaMarkers);
            mAvg.setMarkers(slaMarkers);
        });
    }
}