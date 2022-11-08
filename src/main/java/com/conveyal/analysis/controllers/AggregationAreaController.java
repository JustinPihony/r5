package com.conveyal.analysis.controllers;

import com.conveyal.analysis.UserPermissions;
import com.conveyal.analysis.components.TaskScheduler;
import com.conveyal.analysis.datasource.derivation.AggregationAreaDerivation;
import com.conveyal.analysis.datasource.derivation.DataDerivation;
import com.conveyal.analysis.models.AggregationArea;
import com.conveyal.analysis.persistence.AnalysisDB;
import com.conveyal.analysis.util.JsonUtil;
import com.conveyal.file.FileStorage;
import com.conveyal.r5.analyst.progress.Task;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import spark.Request;
import spark.Response;

import java.lang.invoke.MethodHandles;

import static com.conveyal.analysis.util.JsonUtil.toJson;

/**
 * Stores vector aggregationAreas (used to define the region of a weighted average accessibility metric).
 */
public class AggregationAreaController implements HttpController {

    private static final Logger LOG = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

    private final FileStorage fileStorage;
    private final AnalysisDB db;
    private final TaskScheduler taskScheduler;

    public AggregationAreaController(
            FileStorage fileStorage,
            AnalysisDB db,
            TaskScheduler taskScheduler
    ) {
        this.fileStorage = fileStorage;
        this.db = db;
        this.taskScheduler = taskScheduler;
    }

    /**
     * Create binary .grid files for aggregation (aka mask) areas, save them to FileStorage, and persist their metadata
     * to Mongo. The supplied request (req) must include query parameters specifying the dataSourceId of a
     * SpatialDataSource containing the polygonal aggregation area geometries. If the mergePolygons query parameter is
     * supplied and is true, all polygons will be merged into one large (multi)polygon aggregation area.
     * If the mergePolygons query parameter is not supplied or is false, the nameProperty query parameter must be
     * the name of a text attribute in that SpatialDataSource. One aggregation area will be created for each polygon
     * drawing the names from that attribute.
     *
     * @return the ID of the Task representing the enqueued background action that will create the aggregation areas.
     */
    private String createAggregationAreas(Request req, Response res) {
        // Create and enqueue an asynchronous background action to derive aggregation areas from spatial data source.
        // The constructor will extract query parameters and range check them (not ideal separation, but it works).
        DataDerivation derivation = AggregationAreaDerivation.fromRequest(req, fileStorage, db);
        Task backgroundTask = Task.create("Aggregation area creation: " + derivation.dataSource().name)
                .forUser(UserPermissions.from(req))
                .setHeavy(true)
                .withAction(derivation);

        taskScheduler.enqueue(backgroundTask);
        return backgroundTask.id.toString();
    }

    /** Returns a JSON-wrapped URL for the mask grid of the aggregation area whose id matches the path parameter. */
    private ObjectNode getAggregationAreaGridUrl (Request req, Response res) {
        AggregationArea aggregationArea = db.aggregationAreas.findPermittedByRequestParamId(req);
        String url = fileStorage.getURL(aggregationArea.getStorageKey());
        return JsonUtil.objectNode().put("url", url);
    }

    @Override
    public void registerEndpoints (spark.Service sparkService) {
        sparkService.get("/api/aggregationArea/:_id", this::getAggregationAreaGridUrl, toJson);
        sparkService.post("/api/aggregationArea", this::createAggregationAreas, toJson);
    }

}
