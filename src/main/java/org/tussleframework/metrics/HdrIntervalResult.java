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

package org.tussleframework.metrics;

import java.util.LinkedList;
import java.util.List;
import java.util.stream.DoubleStream;

import org.HdrHistogram.AbstractHistogram;
import org.HdrHistogram.Histogram;
import org.tussleframework.tools.LoggerTool;

public class HdrIntervalResult {

    private static final MetricType[] reportedTypes = {
            MetricType.COUNTS,
            MetricType.P0_VALUES,
            MetricType.P50_VALUES,
            MetricType.P90_VALUES,
            MetricType.P99_VALUES,
            MetricType.P999_VALUES,
            MetricType.P9999_VALUES,
            MetricType.P100_VALUES,
    };

    public static final int reportedTypeCount() {
        return reportedTypes.length;
    }

    public static final MetricType reportedType(int index) {
        return reportedTypes[index];
    }

    private Metric metric;
    private Interval interval;
    private Histogram histogram;
    private double histogramFactor;
    private double[] movingWindowMax;
    private ServiceLevelExpectation[] sleConfig;
    private DoubleStream.Builder[] valBuffers;
    private DoubleStream.Builder[] mwBuffValues;
    private DoubleStream.Builder[] mwBuffCounts;
    private MovingWindowSumHistogram[] movingWindowSumHistograms;

    private static long mul(long v, long m) {
        if (v == Long.MAX_VALUE || v == Long.MIN_VALUE)
            return v;
        else
            return v * m;
    }

    public HdrIntervalResult(MovingWindowSLE[] sleConfig, Interval interval, double histogramFactor) {
        this.sleConfig = sleConfig;
        this.histogram = new Histogram(3);
        this.histogramFactor = histogramFactor;
        this.interval = new Interval(mul(interval.start, 1000), mul(interval.finish , 1000), interval.name);
        valBuffers = new DoubleStream.Builder[reportedTypes.length];
        for (int i = 0; i < valBuffers.length; i++) {
            valBuffers[i] = DoubleStream.builder();
        }
        mwBuffValues = new DoubleStream.Builder[sleConfig.length];
        mwBuffCounts = new DoubleStream.Builder[sleConfig.length];
        for (int i = 0; i < sleConfig.length; i++) {
            mwBuffValues[i] = DoubleStream.builder();
            mwBuffCounts[i] = DoubleStream.builder();
        }
        movingWindowSumHistograms = new MovingWindowSumHistogram[sleConfig.length];
        movingWindowMax = new double[sleConfig.length];
        for (int i = 0; i < sleConfig.length; i++) {
            double percentile = sleConfig[i].percentile;
            int movingWindow = sleConfig[i].movingWindow;
            movingWindowSumHistograms[i] = new MovingWindowSumHistogram(new Histogram(3), new LinkedList<>(), percentile, movingWindow);
        }
    }

    public Metric getMetric() {
        return metric;
    }

    public void adjustInterval(long stamp) {
        interval.adjust(stamp);
    }

    public void add(List<AbstractHistogram> histos) {
        Histogram histoSum = new Histogram(3);
        int added = 0;
        for (AbstractHistogram h : histos) {
            long stamp1 = h.getStartTimeStamp();
            long stamp2 = h.getEndTimeStamp();
            if (interval.contains(stamp1, stamp2)) {
                histogram.add(h);
                histoSum.add(h);
                added++;
                for (int i = 0; i < movingWindowSumHistograms.length; i++) {
                    MovingWindowSumHistogram mwsh = movingWindowSumHistograms[i];
                    mwsh.add(h);
                    double mwValue = mwsh.sumHistogram.getValueAtPercentile(mwsh.percentile) / histogramFactor;
                    long mwCount = mwsh.sumHistogram.getTotalCount();
                    mwBuffValues[i].add(mwValue);
                    mwBuffCounts[i].add(mwCount);
                    if (movingWindowMax[i] < mwValue) {
                        movingWindowMax[i] = mwValue;
                    }
                }
            }
        }
        if (added > 0) {
            for (int i = 0; i < reportedTypes.length; i++) {
                valBuffers[i].add(reportedTypes[i].getValue(histoSum, histogramFactor));
            }
        }
    }

    public void processHistogram(HdrResult hdrResult, MetricData metricData, double[] percentiles, int mergeHistos) {
        String metricIntervalName = (hdrResult.metricName + " " + interval.name).trim();
        long totalCount = histogram.getTotalCount();
        long start = histogram.getStartTimeStamp();
        long finish = histogram.getEndTimeStamp();
        LoggerTool.log("HdrIntervalResult", "processHistogram interval [%s] totalCount %d, start %d, finish %d", interval, totalCount, start, finish);
        if (totalCount == 0 || finish <= start) {
            return;
        }
        metric = Metric.builder()
                .start(start)
                .finish(finish)
                .name(metricIntervalName)
                .units(hdrResult.timeUnits)
                .operation(hdrResult.operationName)
                .delay(hdrResult.intervalLength * mergeHistos)
                .totalValues(totalCount)
                .retry(hdrResult.runArgs.step)
                .percentOfHighBound(hdrResult.runArgs.ratePercent)
                .targetRate(hdrResult.runArgs.targetRate)
                .actualRate(histogram.getTotalCount() / ((finish - start) / 1000.0))
                .meanValue(histogram.getMean() / histogramFactor)
                .build();
        metricData.add(metric);
        for (int i = 0; i < reportedTypes.length; i++) {
            metric.add(new MetricValue(reportedTypes[i].name(), valBuffers[i].build().toArray()));
        }
        DoubleStream.Builder buffPercentileValues = DoubleStream.builder();
        DoubleStream.Builder buffPercentileCounts = DoubleStream.builder();
        long highValue = histogram.getValueAtPercentile(100);
        if (percentiles != null && percentiles.length > 0) {
            for (double p : percentiles) {
                long pValue = histogram.getValueAtPercentile(p);
                buffPercentileValues.add(pValue / histogramFactor);
                buffPercentileCounts.add(histogram.getCountBetweenValues(pValue, highValue));
            }
            metric.add(new MetricValue("PERCENTILE_NAMES", percentiles));
            metric.add(new MetricValue("PERCENTILE_VALUES", buffPercentileValues.build().toArray()));
            metric.add(new MetricValue("PERCENTILE_COUNTS", buffPercentileCounts.build().toArray()));
        }
        for (int i = 0; i < sleConfig.length; i++) {
            LoggerTool.log("HdrIntervalResult", "sleConfig " + sleConfig[i].longName()  + " max " + movingWindowMax[i]);
            String mwMetricName = (hdrResult.metricName + " " + sleConfig[i].longName() + " " + interval.name).trim();
            Metric mwMetric = Metric.builder()
                    .name(mwMetricName)
                    .units(hdrResult.timeUnits)
                    .operation(hdrResult.operationName)
                    .start(start)
                    .finish(finish)
                    .delay(hdrResult.intervalLength)
                    .totalValues(totalCount)
                    .retry(hdrResult.runArgs.step)
                    .percentOfHighBound(hdrResult.runArgs.ratePercent)
                    .targetRate(hdrResult.runArgs.targetRate)
                    //.type("mw")
                    //.group(valName)
                    .build();
            mwMetric.add(new MetricValue("VALUES", mwBuffValues[i].build().toArray()));
            mwMetric.add(new MetricValue("COUNTS", mwBuffCounts[i].build().toArray()));
            if (sleConfig[i].markerValue() > 0) {
                mwMetric.addMarker(new Marker(sleConfig[i].markerName(), null, sleConfig[i].markerValue()));
            }
            metricData.add(mwMetric);
            // TODO: targetMetric.add(new MetricValue(valName + "_max", movingWindowMax[i]))
        }
    }
}