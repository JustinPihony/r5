package com.conveyal.r5.analyst.progress;

import com.conveyal.analysis.models.AggregationArea;
import com.conveyal.analysis.models.Bundle;
import com.conveyal.analysis.models.OpportunityDataset;
import com.conveyal.analysis.models.RegionalAnalysis;
import com.conveyal.analysis.models.SpatialDatasetSource;

/**
 * There is some implicit and unenforced correspondence between these values and those in FileCategory, as well
 * as the tables in Mongo. We should probably clearly state and enforce this parallelism. No background work is
 * done creating regions, projects, or modifications so they don't need to be represented here.
 */
public enum WorkProductType {

    BUNDLE, REGIONAL_ANALYSIS, AGGREGATION_AREA, OPPORTUNITY_DATASET, SPATIAL_DATASET_SOURCE;

    public static WorkProductType forModel (Object model) {
        if (model instanceof Bundle) return BUNDLE;
        if (model instanceof OpportunityDataset) return OPPORTUNITY_DATASET;
        if (model instanceof RegionalAnalysis) return REGIONAL_ANALYSIS;
        if (model instanceof AggregationArea) return AGGREGATION_AREA;
        if (model instanceof SpatialDatasetSource) return SPATIAL_DATASET_SOURCE;
        throw new IllegalArgumentException("Unrecognized work product type.");
    }
}