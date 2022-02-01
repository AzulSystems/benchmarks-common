/*
 * Copyright (c) 2021, Azul Systems
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

package org.benchmarks.metrics;

import org.HdrHistogram.AbstractHistogram;

public enum MetricType {
    COUNTS,
    P0_VALUES,
    P50_VALUES,
    P90_VALUES,
    P99_VALUES,
    P999_VALUES,
    P9999_VALUES,
    P100_VALUES,
    THROUGHPUT,
    ;

    public double getValue(AbstractHistogram histogram, double latencyFactor) {
        switch (this) {
        case COUNTS:
            return histogram.getTotalCount();
        case P0_VALUES:
            return histogram.getMinValue() / latencyFactor;
        case P50_VALUES:
            return histogram.getValueAtPercentile(50) / latencyFactor;
        case P90_VALUES:
            return histogram.getValueAtPercentile(90) / latencyFactor;
        case P99_VALUES:
            return histogram.getValueAtPercentile(99) / latencyFactor;
        case P999_VALUES:
            return histogram.getValueAtPercentile(99.9) / latencyFactor;
        case P9999_VALUES:
            return histogram.getValueAtPercentile(99.99) / latencyFactor;
        case P100_VALUES:
            return histogram.getMaxValue() / latencyFactor;
        case THROUGHPUT:
            long delay = histogram.getEndTimeStamp() - histogram.getStartTimeStamp();
            return delay > 0 ? histogram.getTotalCount() * 1000.0 / delay : -1;
        default:
            return -1;
        }
    }
}
