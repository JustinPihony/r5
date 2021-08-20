package com.conveyal.analysis.controllers;

import com.conveyal.analysis.UserPermissions;
import com.conveyal.analysis.components.TaskScheduler;
import com.conveyal.analysis.datasource.DataSourceIngester;
import com.conveyal.analysis.grids.SeamlessCensusGridExtractor;
import com.conveyal.analysis.models.DataSource;
import com.conveyal.analysis.models.GtfsDataSource;
import com.conveyal.analysis.models.OsmDataSource;
import com.conveyal.analysis.models.SpatialDataSource;
import com.conveyal.analysis.persistence.AnalysisCollection;
import com.conveyal.analysis.persistence.AnalysisDB;
import com.conveyal.analysis.util.HttpUtils;
import com.conveyal.file.FileStorage;
import com.conveyal.r5.analyst.progress.Task;
import com.conveyal.r5.analyst.progress.WorkProduct;
import org.apache.commons.fileupload.FileItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import spark.Request;
import spark.Response;

import java.util.Collection;
import java.util.List;
import java.util.Map;

import static com.conveyal.analysis.util.JsonUtil.toJson;
import static com.conveyal.r5.analyst.WebMercatorGridPointSet.parseZoom;
import static com.conveyal.r5.analyst.progress.WorkProductType.DATA_SOURCE;
import static com.mongodb.client.model.Filters.and;
import static com.mongodb.client.model.Filters.eq;

/**
 * Controller that handles CRUD of DataSources, which are Mongo metadata about user-uploaded files.
 * Unlike some Mongo documents, these are mostly created and updated by backend validation and processing methods.
 * Currently this handles only one subtype: SpatialDataSource, which represents GIS-like geospatial feature data.
 */
public class DataSourceController implements HttpController {

    private static final Logger LOG = LoggerFactory.getLogger(DataSourceController.class);

    // Component Dependencies
    private final FileStorage fileStorage;
    private final TaskScheduler taskScheduler;
    private final SeamlessCensusGridExtractor extractor;

    // Collection in the database holding all our DataSources, which can be of several subtypes.
    private final AnalysisCollection<DataSource> dataSourceCollection;

    public DataSourceController (
            FileStorage fileStorage,
            AnalysisDB database,
            TaskScheduler taskScheduler,
            SeamlessCensusGridExtractor extractor
    ) {
        this.fileStorage = fileStorage;
        this.taskScheduler = taskScheduler;
        this.extractor = extractor;
        // We don't hold on to the AnalysisDB Component, just get one collection from it.
        // Register all the subclasses so the Mongo driver will recognize their discriminators.
        this.dataSourceCollection = database.getAnalysisCollection(
            "dataSources", DataSource.class, SpatialDataSource.class, OsmDataSource.class, GtfsDataSource.class
        );
    }

    /** HTTP GET: Retrieve all DataSource records, filtered by the (required) regionId query parameter. */
    private List<DataSource> getAllDataSourcesForRegion (Request req, Response res) {
        return dataSourceCollection.findPermitted(
                eq("regionId", req.queryParams("regionId")), UserPermissions.from(req)
        );
    }

    /** HTTP GET: Retrieve a single DataSource record by the ID supplied in the URL path parameter. */
    private DataSource getOneDataSourceById (Request req, Response res) {
        return dataSourceCollection.findPermittedByRequestParamId(req, res);
    }

    /** HTTP DELETE: Delete a single DataSource record and associated files in FileStorage by supplied ID parameter. */
    private String deleteOneDataSourceById (Request request, Response response) {
        dataSourceCollection.deleteByIdParamIfPermitted(request).getDeletedCount();
        return "DELETE";
        // TODO delete files from storage
        // TODO delete referencing database records
        //      Shouldn't this be deleting by ID instead of sending the whole document?
        // TODO why do our delete methods return a list of documents? Can we just return the ID or HTTP status code?
    }

    private SpatialDataSource downloadLODES(Request req, Response res) {
        final String regionId = req.params("regionId");
        final int zoom = parseZoom(req.queryParams("zoom"));
        UserPermissions userPermissions = UserPermissions.from(req);
        SpatialDataSource source = new SpatialDataSource(userPermissions, extractor.sourceName);
        source.regionId = regionId;

        taskScheduler.enqueue(Task.create("Extracting LODES data")
                .forUser(userPermissions)
                .setHeavy(true)
                .withWorkProduct(source)
                .withAction((progressListener) -> {
                    // TODO implement
                }));

        return source;
    }

    /**
     * A file is posted to this endpoint to create a new DataSource. It is validated and metadata are extracted.
     * The request should be a multipart/form-data POST request, containing uploaded files and associated parameters.
     * In a standard REST API, a post would return the ID of the newly created DataSource. Here we're starting an async
     * background process, so we return the task ID or the ID its work product (the DataSource)?
     */
    private String handleUpload (Request req, Response res) {
        final UserPermissions userPermissions = UserPermissions.from(req);
        final Map<String, List<FileItem>> formFields = HttpUtils.getRequestFiles(req.raw());

        DataSourceIngester ingester = DataSourceIngester.forFormFields(
                fileStorage, dataSourceCollection, formFields, userPermissions
        );

        Task backgroundTask = Task.create("Processing uploaded files: " + ingester.getDataSourceName())
                .forUser(userPermissions)
                //.withWorkProduct(dataSource)
                // or should TaskActions have a method to return their work product?
                // Or a WorkProductDescriptor, with type, region, and ID?
                // TaskActions could define methods to return a title, workProductDescriptor, etc.
                // Then we just have taskScheduler.enqueue(Task.forAction(user, ingester));
                // To the extent that TaskActions are named types instead of lambdas, they can create their workproduct
                // upon instantiation and return it via a method.
                // ProgressListener could also have a setWorkProduct method.
                .withWorkProduct(DATA_SOURCE, ingester.getDataSourceId(), ingester.getRegionId())
                .withAction(ingester);

        taskScheduler.enqueue(backgroundTask);
        return backgroundTask.id.toString();
    }

    @Override
    public void registerEndpoints (spark.Service sparkService) {
        sparkService.path("/api/datasource", () -> {
            sparkService.get("/", this::getAllDataSourcesForRegion, toJson);
            sparkService.get("/:_id", this::getOneDataSourceById, toJson);
            sparkService.delete("/:_id", this::deleteOneDataSourceById, toJson);
            sparkService.post("", this::handleUpload, toJson);
            // regionId will be in query parameter
            sparkService.post("/addLodesDataSource", this::downloadLODES, toJson);
        });
    }
}