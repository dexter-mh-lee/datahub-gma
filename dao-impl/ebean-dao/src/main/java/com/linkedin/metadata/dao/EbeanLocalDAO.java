package com.linkedin.metadata.dao;

import com.google.common.annotations.VisibleForTesting;
import com.linkedin.common.AuditStamp;
import com.linkedin.common.urn.Urn;
import com.linkedin.data.template.RecordTemplate;
import com.linkedin.data.template.UnionTemplate;
import com.linkedin.metadata.dao.exception.ModelConversionException;
import com.linkedin.metadata.dao.exception.RetryLimitReached;
import com.linkedin.metadata.dao.producer.BaseMetadataEventProducer;
import com.linkedin.metadata.dao.retention.TimeBasedRetention;
import com.linkedin.metadata.dao.retention.VersionBasedRetention;
import com.linkedin.metadata.dao.scsi.EmptyPathExtractor;
import com.linkedin.metadata.dao.scsi.UrnPathExtractor;
import com.linkedin.metadata.dao.storage.LocalDAOStorageConfig;
import com.linkedin.metadata.dao.utils.ModelUtils;
import com.linkedin.metadata.dao.utils.QueryUtils;
import com.linkedin.metadata.dao.utils.RecordUtils;
import com.linkedin.metadata.query.Condition;
import com.linkedin.metadata.query.ExtraInfo;
import com.linkedin.metadata.query.ExtraInfoArray;
import com.linkedin.metadata.query.IndexCriterion;
import com.linkedin.metadata.query.IndexCriterionArray;
import com.linkedin.metadata.query.IndexFilter;
import com.linkedin.metadata.query.IndexValue;
import com.linkedin.metadata.query.ListResultMetadata;
import io.ebean.DuplicateKeyException;
import io.ebean.EbeanServer;
import io.ebean.EbeanServerFactory;
import io.ebean.ExpressionList;
import io.ebean.PagedList;
import io.ebean.Query;
import io.ebean.Transaction;
import io.ebean.config.ServerConfig;
import io.ebean.datasource.DataSourceConfig;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URISyntaxException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.persistence.RollbackException;
import javax.persistence.Table;
import lombok.Value;

import static com.linkedin.metadata.dao.EbeanMetadataAspect.*;


/**
 * An Ebean implementation of {@link BaseLocalDAO}.
 */
public class EbeanLocalDAO<ASPECT_UNION extends UnionTemplate, URN extends Urn>
    extends BaseLocalDAO<ASPECT_UNION, URN> {

  private static final int INDEX_QUERY_TIMEOUT_IN_SEC = 5;
  private static final String EBEAN_MODEL_PACKAGE = EbeanMetadataAspect.class.getPackage().getName();
  private static final String EBEAN_INDEX_PACKAGE = EbeanMetadataIndex.class.getPackage().getName();

  protected final EbeanServer _server;
  protected final Class<URN> _urnClass;
  private UrnPathExtractor<URN> _urnPathExtractor;
  private int _queryKeysCount = 0; // 0 means no pagination on keys

  // TODO feature flag, remove when vetted.
  private boolean _useUnionForBatch = false;

  @Value
  static class GMAIndexPair {
    public String valueType;
    public Object value;
  }

  private static final Map<Condition, String> CONDITION_STRING_MAP =
      Collections.unmodifiableMap(new HashMap<Condition, String>() {
        {
          put(Condition.EQUAL, "=");
          put(Condition.GREATER_THAN, ">");
          put(Condition.GREATER_THAN_OR_EQUAL_TO, ">=");
          put(Condition.LESS_THAN, "<");
          put(Condition.LESS_THAN_OR_EQUAL_TO, "<=");
          put(Condition.START_WITH, "LIKE");
        }
      });

  @VisibleForTesting
  EbeanLocalDAO(@Nonnull Class<ASPECT_UNION> aspectUnionClass, @Nonnull BaseMetadataEventProducer producer,
      @Nonnull EbeanServer server, @Nonnull Class<URN> urnClass) {
    super(aspectUnionClass, producer);
    _server = server;
    _urnClass = urnClass;
    _urnPathExtractor = new EmptyPathExtractor<>();
  }

  /**
   * Constructor for EbeanLocalDAO.
   *
   * @param aspectUnionClass containing union of all supported aspects. Must be a valid aspect union defined in com.linkedin.metadata.aspect
   * @param producer {@link BaseMetadataEventProducer} for the metadata event producer
   * @param serverConfig {@link ServerConfig} that defines the configuration of EbeanServer instances
   * @param urnClass Class of the entity URN
   */
  public EbeanLocalDAO(@Nonnull Class<ASPECT_UNION> aspectUnionClass, @Nonnull BaseMetadataEventProducer producer,
      @Nonnull ServerConfig serverConfig, @Nonnull Class<URN> urnClass) {
    this(aspectUnionClass, producer, createServer(serverConfig), urnClass);
  }

  @VisibleForTesting
  EbeanLocalDAO(@Nonnull BaseMetadataEventProducer producer, @Nonnull EbeanServer server,
      @Nonnull LocalDAOStorageConfig storageConfig, @Nonnull Class<URN> urnClass,
      @Nonnull UrnPathExtractor<URN> urnPathExtractor) {
    super(producer, storageConfig);
    _server = server;
    _urnClass = urnClass;
    _urnPathExtractor = urnPathExtractor;
  }

  @VisibleForTesting
  EbeanLocalDAO(@Nonnull BaseMetadataEventProducer producer, @Nonnull EbeanServer server,
      @Nonnull LocalDAOStorageConfig storageConfig, @Nonnull Class<URN> urnClass) {
    this(producer, server, storageConfig, urnClass, new EmptyPathExtractor<>());
  }

  /**
   * Constructor for EbeanLocalDAO.
   *
   * @param producer {@link BaseMetadataEventProducer} for the metadata event producer
   * @param serverConfig {@link ServerConfig} that defines the configuration of EbeanServer instances
   * @param storageConfig {@link LocalDAOStorageConfig} containing storage config of full list of supported aspects
   * @param urnClass class of the entity URN
   * @param urnPathExtractor path extractor to index parts of URNs to the secondary index
   */
  public EbeanLocalDAO(@Nonnull BaseMetadataEventProducer producer, @Nonnull ServerConfig serverConfig,
      @Nonnull LocalDAOStorageConfig storageConfig, @Nonnull Class<URN> urnClass,
      @Nonnull UrnPathExtractor<URN> urnPathExtractor) {
    this(producer, createServer(serverConfig), storageConfig, urnClass, urnPathExtractor);
  }

  /**
   * Constructor for EbeanLocalDAO.
   *
   * @param producer {@link BaseMetadataEventProducer} for the metadata event producer
   * @param serverConfig {@link ServerConfig} that defines the configuration of EbeanServer instances
   * @param storageConfig {@link LocalDAOStorageConfig} containing storage config of full list of supported aspects
   * @param urnClass class of the entity URN
   */
  public EbeanLocalDAO(@Nonnull BaseMetadataEventProducer producer, @Nonnull ServerConfig serverConfig,
      @Nonnull LocalDAOStorageConfig storageConfig, @Nonnull Class<URN> urnClass) {
    this(producer, createServer(serverConfig), storageConfig, urnClass, new EmptyPathExtractor<>());
  }

  /**
   * Determines whether we should use UNION ALL statements for batch gets, rather than a large series of OR statements.
   *
   * <p>DO NOT USE THIS FLAG! This is for LinkedIn use to help us test this feature without a rollback. Once we've
   * vetted this in production we will be removing this flag and making the the default behavior. So if you set this
   * to true by calling this method, your code will break when we remove this method. Just wait a bit for us to turn
   * it on by default!
   *
   * <p>While this can increase performance, it can also cause a stack overflow error if {@link #setQueryKeysCount(int)}
   * is either not set or set too high. See https://groups.google.com/g/ebean/c/ILpii41dJPA/m/VxMbPlqEBwAJ.
   */
  public void setUseUnionForBatch(boolean useUnionForBatch) {
    _useUnionForBatch = useUnionForBatch;
  }

  @Nonnull
  private static EbeanServer createServer(@Nonnull ServerConfig serverConfig) {
    // Make sure that the serverConfig includes the package that contains DAO's Ebean model.
    if (!serverConfig.getPackages().contains(EBEAN_MODEL_PACKAGE)) {
      serverConfig.getPackages().add(EBEAN_MODEL_PACKAGE);
    }
    if (!serverConfig.getPackages().contains(EBEAN_INDEX_PACKAGE)) {
      serverConfig.getPackages().add(EBEAN_INDEX_PACKAGE);
    }
    return EbeanServerFactory.create(serverConfig);
  }

  /**
   * Return the {@link EbeanServer} server instance used for customized queries.
   */
  public EbeanServer getServer() {
    return _server;
  }

  public void setUrnPathExtractor(@Nonnull UrnPathExtractor<URN> urnPathExtractor) {
    _urnPathExtractor = urnPathExtractor;
  }

  /**
   * Creates a private in-memory {@link EbeanServer} based on H2 for production.
   */
  @Nonnull
  public static ServerConfig createProductionH2ServerConfig(@Nonnull String dbName) {

    DataSourceConfig dataSourceConfig = new DataSourceConfig();
    dataSourceConfig.setUsername("tester");
    dataSourceConfig.setPassword("");
    String url = "jdbc:h2:mem:" + dbName + ";IGNORECASE=TRUE;DB_CLOSE_DELAY=-1;";
    dataSourceConfig.setUrl(url);
    dataSourceConfig.setDriver("org.h2.Driver");

    ServerConfig serverConfig = new ServerConfig();
    serverConfig.setName(dbName);
    serverConfig.setDataSourceConfig(dataSourceConfig);
    serverConfig.setDdlGenerate(false);
    serverConfig.setDdlRun(false);

    return serverConfig;
  }

  /**
   * Creates a private in-memory {@link EbeanServer} based on H2 for testing purpose.
   */
  @Nonnull
  public static ServerConfig createTestingH2ServerConfig() {
    DataSourceConfig dataSourceConfig = new DataSourceConfig();
    dataSourceConfig.setUsername("tester");
    dataSourceConfig.setPassword("");
    dataSourceConfig.setUrl("jdbc:h2:mem:;IGNORECASE=TRUE;");
    dataSourceConfig.setDriver("org.h2.Driver");

    ServerConfig serverConfig = new ServerConfig();
    serverConfig.setName("gma");
    serverConfig.setDataSourceConfig(dataSourceConfig);
    serverConfig.setDdlGenerate(true);
    serverConfig.setDdlRun(true);

    return serverConfig;
  }

  @Nonnull
  @Override
  protected <T> T runInTransactionWithRetry(@Nonnull Supplier<T> block, int maxTransactionRetry) {
    int retryCount = 0;
    Exception lastException;

    T result = null;
    do {
      try (Transaction transaction = _server.beginTransaction()) {
        result = block.get();
        transaction.commit();
        lastException = null;
        break;
      } catch (RollbackException | DuplicateKeyException exception) {
        lastException = exception;
      }
    } while (++retryCount <= maxTransactionRetry);

    if (lastException != null) {
      throw new RetryLimitReached("Failed to add after " + maxTransactionRetry + " retries", lastException);
    }

    return result;
  }

  @Override
  protected <ASPECT extends RecordTemplate> long saveLatest(@Nonnull URN urn, @Nonnull Class<ASPECT> aspectClass,
      @Nullable ASPECT oldValue, @Nullable AuditStamp oldAuditStamp, @Nonnull ASPECT newValue,
      @Nonnull AuditStamp newAuditStamp) {
    // Save oldValue as the largest version + 1
    long largestVersion = 0;
    if (oldValue != null && oldAuditStamp != null) {
      largestVersion = getNextVersion(urn, aspectClass);
      save(urn, oldValue, oldAuditStamp, largestVersion, true);
    }

    // Save newValue as the latest version (v0)
    save(urn, newValue, newAuditStamp, LATEST_VERSION, oldValue == null);
    return largestVersion;
  }

  @Override
  protected <ASPECT extends RecordTemplate> void updateLocalIndex(@Nonnull URN urn, @Nonnull ASPECT newValue,
      long version) {
    if (!isLocalSecondaryIndexEnabled()) {
      throw new UnsupportedOperationException("Local secondary index isn't supported");
    }

    // Process and save URN
    // Only do this with the first version of each aspect
    if (version == FIRST_VERSION) {
      updateUrnInLocalIndex(urn);
    }
    updateAspectInLocalIndex(urn, newValue);
  }

  @Override
  @Nullable
  protected <ASPECT extends RecordTemplate> AspectEntry<ASPECT> getLatest(@Nonnull URN urn,
      @Nonnull Class<ASPECT> aspectClass) {
    final PrimaryKey key = new PrimaryKey(urn.toString(), ModelUtils.getAspectName(aspectClass), 0L);
    final EbeanMetadataAspect latest = _server.find(EbeanMetadataAspect.class, key);
    if (latest == null) {
      return null;
    }

    return new AspectEntry<>(RecordUtils.toRecordTemplate(aspectClass, latest.getMetadata()), toExtraInfo(latest));
  }

  @Override
  protected void save(@Nonnull URN urn, @Nonnull RecordTemplate value, @Nonnull AuditStamp auditStamp, long version,
      boolean insert) {

    final String aspectName = ModelUtils.getAspectName(value.getClass());

    final EbeanMetadataAspect aspect = new EbeanMetadataAspect();
    aspect.setKey(new PrimaryKey(urn.toString(), aspectName, version));
    aspect.setMetadata(RecordUtils.toJsonString(value));
    aspect.setCreatedOn(new Timestamp(auditStamp.getTime()));
    aspect.setCreatedBy(auditStamp.getActor().toString());

    Urn impersonator = auditStamp.getImpersonator();
    if (impersonator != null) {
      aspect.setCreatedFor(impersonator.toString());
    }

    if (insert) {
      _server.insert(aspect);
    } else {
      _server.update(aspect);
    }
  }

  protected long saveSingleRecordToLocalIndex(@Nonnull URN urn, @Nonnull String aspect, @Nonnull String path,
      @Nonnull Object value) {

    final EbeanMetadataIndex record = new EbeanMetadataIndex().setUrn(urn.toString()).setAspect(aspect).setPath(path);
    if (value instanceof Integer || value instanceof Long) {
      record.setLongVal(Long.valueOf(value.toString()));
    } else if (value instanceof Float || value instanceof Double) {
      record.setDoubleVal(Double.valueOf(value.toString()));
    } else {
      record.setStringVal(value.toString());
    }

    _server.insert(record);
    return record.getId();
  }

  @Nonnull
  Map<Class<? extends RecordTemplate>, LocalDAOStorageConfig.AspectStorageConfig> getStrongConsistentIndexPaths() {
    return Collections.unmodifiableMap(new HashMap<>(_storageConfig.getAspectStorageConfigMap()));
  }

  private void updateUrnInLocalIndex(@Nonnull URN urn) {
    if (existsInLocalIndex(urn)) {
      return;
    }

    final Map<String, Object> pathValueMap = _urnPathExtractor.extractPaths(urn);
    pathValueMap.forEach(
        (path, value) -> saveSingleRecordToLocalIndex(urn, urn.getClass().getCanonicalName(), path, value));
  }

  private <ASPECT extends RecordTemplate> void updateAspectInLocalIndex(@Nonnull URN urn, @Nonnull ASPECT newValue) {

    if (!_storageConfig.getAspectStorageConfigMap().containsKey(newValue.getClass())
        || _storageConfig.getAspectStorageConfigMap().get(newValue.getClass()) == null) {
      return;
    }
    // step1: remove all rows from the index table corresponding to <urn, aspect> pair
    _server.find(EbeanMetadataIndex.class)
        .where()
        .eq(URN_COLUMN, urn.toString())
        .eq(ASPECT_COLUMN, ModelUtils.getAspectName(newValue.getClass()))
        .delete();

    // step2: add fields of the aspect that need to be indexed
    final Map<String, LocalDAOStorageConfig.PathStorageConfig> pathStorageConfigMap =
        _storageConfig.getAspectStorageConfigMap().get(newValue.getClass()).getPathStorageConfigMap();

    pathStorageConfigMap.keySet()
        .stream()
        .filter(path -> pathStorageConfigMap.get(path).isStrongConsistentSecondaryIndex())
        .collect(Collectors.toMap(Function.identity(), path -> RecordUtils.getFieldValue(newValue, path)))
        .forEach((k, v) -> v.ifPresent(
            value -> saveSingleRecordToLocalIndex(urn, newValue.getClass().getCanonicalName(), k, value)));
  }

  @Override
  protected <ASPECT extends RecordTemplate> long getNextVersion(@Nonnull URN urn, @Nonnull Class<ASPECT> aspectClass) {

    final List<PrimaryKey> result = _server.find(EbeanMetadataAspect.class)
        .where()
        .eq(URN_COLUMN, urn.toString())
        .eq(ASPECT_COLUMN, ModelUtils.getAspectName(aspectClass))
        .orderBy()
        .desc(VERSION_COLUMN)
        .setMaxRows(1)
        .findIds();

    return result.isEmpty() ? 0 : result.get(0).getVersion() + 1L;
  }

  @Override
  protected <ASPECT extends RecordTemplate> void applyVersionBasedRetention(@Nonnull Class<ASPECT> aspectClass,
      @Nonnull URN urn, @Nonnull VersionBasedRetention retention, long largestVersion) {
    _server.find(EbeanMetadataAspect.class)
        .where()
        .eq(URN_COLUMN, urn.toString())
        .eq(ASPECT_COLUMN, ModelUtils.getAspectName(aspectClass))
        .ne(VERSION_COLUMN, LATEST_VERSION)
        .le(VERSION_COLUMN, largestVersion - retention.getMaxVersionsToRetain() + 1)
        .delete();
  }

  @Override
  protected <ASPECT extends RecordTemplate> void applyTimeBasedRetention(@Nonnull Class<ASPECT> aspectClass,
      @Nonnull URN urn, @Nonnull TimeBasedRetention retention, long currentTime) {

    _server.find(EbeanMetadataAspect.class)
        .where()
        .eq(URN_COLUMN, urn.toString())
        .eq(ASPECT_COLUMN, ModelUtils.getAspectName(aspectClass))
        .lt(CREATED_ON_COLUMN, new Timestamp(currentTime - retention.getMaxAgeToRetain()))
        .delete();
  }

  @Override
  @Nonnull
  public Map<AspectKey<URN, ? extends RecordTemplate>, Optional<? extends RecordTemplate>> get(
      @Nonnull Set<AspectKey<URN, ? extends RecordTemplate>> keys) {
    if (keys.isEmpty()) {
      return Collections.emptyMap();
    }

    final List<EbeanMetadataAspect> records;

    if (_queryKeysCount == 0) {
      records = batchGet(keys, keys.size());
    } else {
      records = batchGet(keys, _queryKeysCount);
    }

    // TODO: Improve this O(n^2) search
    return keys.stream()
        .collect(Collectors.toMap(Function.identity(), key -> records.stream()
            .filter(record -> matchKeys(key, record.getKey()))
            .findFirst()
            .map(record -> toRecordTemplate(key.getAspectClass(), record))));
  }

  @Override
  @Nonnull
  public Map<AspectKey<URN, ? extends RecordTemplate>, AspectWithExtraInfo<? extends RecordTemplate>> getWithExtraInfo(
      @Nonnull Set<AspectKey<URN, ? extends RecordTemplate>> keys) {
    if (keys.isEmpty()) {
      return Collections.emptyMap();
    }

    final List<EbeanMetadataAspect> records = batchGet(keys, keys.size());

    final Map<AspectKey<URN, ? extends RecordTemplate>, AspectWithExtraInfo<? extends RecordTemplate>> result =
        new HashMap<>();
    keys.forEach(key -> records.stream()
        .filter(record -> matchKeys(key, record.getKey()))
        .findFirst()
        .map(record -> result.put(key, toRecordTemplateWithExtraInfo(key.getAspectClass(), record))));
    return result;
  }

  public boolean existsInLocalIndex(@Nonnull URN urn) {
    return _server.find(EbeanMetadataIndex.class).where().eq(URN_COLUMN, urn.toString()).exists();
  }

  /**
   * Sets the max keys allowed for each single query.
   */
  public void setQueryKeysCount(int keysCount) {
    if (keysCount < 0) {
      throw new IllegalArgumentException("Query keys count must be non-negative: " + keysCount);
    }
    _queryKeysCount = keysCount;
  }

  /**
   * BatchGet that allows pagination on keys to avoid large queries.
   * TODO: can further improve by running the sub queries in parallel
   *
   * @param keys a set of keys with urn, aspect and version
   * @param keysCount the max number of keys for each sub query
   */
  @Nonnull
  private List<EbeanMetadataAspect> batchGet(@Nonnull Set<AspectKey<URN, ? extends RecordTemplate>> keys,
      int keysCount) {

    int position = 0;
    final int totalPageCount = QueryUtils.getTotalPageCount(keys.size(), keysCount);

    List<EbeanMetadataAspect> finalResult = batchGetHelper(new ArrayList<>(keys), keysCount, position);
    while (QueryUtils.hasMore(position, keysCount, totalPageCount)) {
      position += keysCount;
      final List<EbeanMetadataAspect> oneStatementResult = batchGetHelper(new ArrayList<>(keys), keysCount, position);
      finalResult.addAll(oneStatementResult);
    }
    return finalResult;
  }

  /**
   * Builds a single SELECT statement for batch get, which selects one entity, and then can be UNION'd with other SELECT
   * statements.
   */
  private String batchGetSelect(@Nonnull String urn, @Nonnull String aspect, long version,
      @Nonnull List<Object> outputParams) {
    outputParams.add(urn);
    outputParams.add(aspect);
    outputParams.add(version);

    return String.format("SELECT t.urn, t.aspect, t.version, t.metadata, t.createdOn, t.createdBy, t.createdFor "
            + "FROM %s t WHERE urn = ? AND aspect = ? AND version = ?",
        EbeanMetadataAspect.class.getAnnotation(Table.class).name());
  }

  @Nonnull
  private List<EbeanMetadataAspect> batchGetUnion(@Nonnull List<AspectKey<URN, ? extends RecordTemplate>> keys,
      int keysCount, int position) {

    // Build one SELECT per key and then UNION ALL the results. This can be much more performant than OR'ing the
    // conditions together. Our query will look like:
    //   SELECT * FROM metadata_aspect WHERE urn = 'urn0' AND aspect = 'aspect0' AND version = 0
    //   UNION ALL
    //   SELECT * FROM metadata_aspect WHERE urn = 'urn0' AND aspect = 'aspect1' AND version = 0
    //   ...
    // Note: UNION ALL should be safe and more performant than UNION. We're selecting the entire entity key (as well
    // as data), so each result should be unique. No need to deduplicate.
    // Another note: ebean doesn't support UNION ALL, so we need to manually build the SQL statement ourselves.
    final StringBuilder sb = new StringBuilder();
    final int end = Math.min(keys.size(), position + keysCount);
    final List<Object> params = new ArrayList<>();
    for (int index = position; index < end; index++) {
      sb.append(batchGetSelect(keys.get(index).getUrn().toString(),
          ModelUtils.getAspectName(keys.get(index).getAspectClass()), keys.get(index).getVersion(), params));

      if (index != end - 1) {
        sb.append(" UNION ALL ");
      }
    }

    final Query<EbeanMetadataAspect> query = _server.findNative(EbeanMetadataAspect.class, sb.toString());

    for (int i = 1; i <= params.size(); i++) {
      query.setParameter(i, params.get(i - 1));
    }

    return query.findList();
  }

  @Nonnull
  private List<EbeanMetadataAspect> batchGetOr(@Nonnull List<AspectKey<URN, ? extends RecordTemplate>> keys,
      int keysCount, int position) {
    ExpressionList<EbeanMetadataAspect> query = _server.find(EbeanMetadataAspect.class).select(ALL_COLUMNS).where();

    // add or if it is not the last element
    if (position != keys.size() - 1) {
      query = query.or();
    }

    for (int index = position; index < keys.size() && index < position + keysCount; index++) {
      query = query.and()
          .eq(URN_COLUMN, keys.get(index).getUrn().toString())
          .eq(ASPECT_COLUMN, ModelUtils.getAspectName(keys.get(index).getAspectClass()))
          .eq(VERSION_COLUMN, keys.get(index).getVersion())
          .endAnd();
    }

    return query.findList();
  }

  @Nonnull
  private List<EbeanMetadataAspect> batchGetHelper(@Nonnull List<AspectKey<URN, ? extends RecordTemplate>> keys,
      int keysCount, int position) {
    // TODO remove batchGetOr, make batchGetUnion the only implementation.
    if (_useUnionForBatch) {
      return batchGetUnion(keys, keysCount, position);
    } else {
      return batchGetOr(keys, keysCount, position);
    }
  }

  /**
   * Checks if an {@link AspectKey} and a {@link PrimaryKey} for Ebean are equivalent.
   *
   * @param aspectKey Urn needs to do a ignore case match
   */
  private boolean matchKeys(@Nonnull AspectKey<URN, ? extends RecordTemplate> aspectKey, @Nonnull PrimaryKey pk) {
    return aspectKey.getUrn().toString().equalsIgnoreCase(pk.getUrn()) && aspectKey.getVersion() == pk.getVersion()
        && ModelUtils.getAspectName(aspectKey.getAspectClass()).equals(pk.getAspect());
  }

  @Override
  @Nonnull
  public <ASPECT extends RecordTemplate> ListResult<Long> listVersions(@Nonnull Class<ASPECT> aspectClass,
      @Nonnull URN urn, int start, int pageSize) {

    checkValidAspect(aspectClass);

    final PagedList<EbeanMetadataAspect> pagedList = _server.find(EbeanMetadataAspect.class)
        .select(KEY_ID)
        .where()
        .eq(URN_COLUMN, urn.toString())
        .eq(ASPECT_COLUMN, ModelUtils.getAspectName(aspectClass))
        .setFirstRow(start)
        .setMaxRows(pageSize)
        .orderBy()
        .asc(VERSION_COLUMN)
        .findPagedList();

    final List<Long> versions =
        pagedList.getList().stream().map(a -> a.getKey().getVersion()).collect(Collectors.toList());
    return toListResult(versions, null, pagedList, start);
  }

  @Override
  @Nonnull
  public <ASPECT extends RecordTemplate> ListResult<URN> listUrns(@Nonnull Class<ASPECT> aspectClass, int start,
      int pageSize) {

    checkValidAspect(aspectClass);

    final PagedList<EbeanMetadataAspect> pagedList = _server.find(EbeanMetadataAspect.class)
        .select(KEY_ID)
        .where()
        .eq(ASPECT_COLUMN, ModelUtils.getAspectName(aspectClass))
        .eq(VERSION_COLUMN, LATEST_VERSION)
        .setFirstRow(start)
        .setMaxRows(pageSize)
        .orderBy()
        .asc(URN_COLUMN)
        .findPagedList();

    final List<URN> urns =
        pagedList.getList().stream().map(entry -> getUrn(entry.getKey().getUrn())).collect(Collectors.toList());
    return toListResult(urns, null, pagedList, start);
  }

  @Override
  @Nonnull
  public <ASPECT extends RecordTemplate> ListResult<ASPECT> list(@Nonnull Class<ASPECT> aspectClass, @Nonnull URN urn,
      int start, int pageSize) {

    checkValidAspect(aspectClass);

    final PagedList<EbeanMetadataAspect> pagedList = _server.find(EbeanMetadataAspect.class)
        .select(ALL_COLUMNS)
        .where()
        .eq(URN_COLUMN, urn.toString())
        .eq(ASPECT_COLUMN, ModelUtils.getAspectName(aspectClass))
        .setFirstRow(start)
        .setMaxRows(pageSize)
        .orderBy()
        .asc(VERSION_COLUMN)
        .findPagedList();

    final List<ASPECT> aspects =
        pagedList.getList().stream().map(a -> toRecordTemplate(aspectClass, a)).collect(Collectors.toList());
    final ListResultMetadata listResultMetadata = makeListResultMetadata(
        pagedList.getList().stream().map(EbeanLocalDAO::toExtraInfo).collect(Collectors.toList()));
    return toListResult(aspects, listResultMetadata, pagedList, start);
  }

  @Override
  @Nonnull
  public <ASPECT extends RecordTemplate> ListResult<ASPECT> list(@Nonnull Class<ASPECT> aspectClass, long version,
      int start, int pageSize) {

    checkValidAspect(aspectClass);

    final PagedList<EbeanMetadataAspect> pagedList = _server.find(EbeanMetadataAspect.class)
        .select(ALL_COLUMNS)
        .where()
        .eq(ASPECT_COLUMN, ModelUtils.getAspectName(aspectClass))
        .eq(VERSION_COLUMN, version)
        .setFirstRow(start)
        .setMaxRows(pageSize)
        .orderBy()
        .asc(URN_COLUMN)
        .findPagedList();

    final List<ASPECT> aspects =
        pagedList.getList().stream().map(a -> toRecordTemplate(aspectClass, a)).collect(Collectors.toList());
    final ListResultMetadata listResultMetadata = makeListResultMetadata(
        pagedList.getList().stream().map(EbeanLocalDAO::toExtraInfo).collect(Collectors.toList()));
    return toListResult(aspects, listResultMetadata, pagedList, start);
  }

  @Override
  @Nonnull
  public <ASPECT extends RecordTemplate> ListResult<ASPECT> list(@Nonnull Class<ASPECT> aspectClass, int start,
      int pageSize) {
    return list(aspectClass, LATEST_VERSION, start, pageSize);
  }

  @Nonnull
  URN getUrn(@Nonnull String urn) {
    try {
      final Method getUrn = _urnClass.getMethod("createFromString", String.class);
      return _urnClass.cast(getUrn.invoke(null, urn));
    } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
      throw new IllegalArgumentException("URN conversion error for " + urn, e);
    }
  }

  @Nonnull
  private static <ASPECT extends RecordTemplate> ASPECT toRecordTemplate(@Nonnull Class<ASPECT> aspectClass,
      @Nonnull EbeanMetadataAspect aspect) {
    return RecordUtils.toRecordTemplate(aspectClass, aspect.getMetadata());
  }

  @Nonnull
  private static <ASPECT extends RecordTemplate> AspectWithExtraInfo<ASPECT> toRecordTemplateWithExtraInfo(
      @Nonnull Class<ASPECT> aspectClass, @Nonnull EbeanMetadataAspect aspect) {
    return new AspectWithExtraInfo<>(RecordUtils.toRecordTemplate(aspectClass, aspect.getMetadata()),
        toExtraInfo(aspect));
  }

  @Nonnull
  private <T> ListResult<T> toListResult(@Nonnull List<T> values, @Nullable ListResultMetadata listResultMetadata,
      @Nonnull PagedList<?> pagedList, @Nullable Integer start) {
    final int nextStart =
        (start != null && pagedList.hasNext()) ? start + pagedList.getList().size() : ListResult.INVALID_NEXT_START;
    return ListResult.<T>builder()
        // Format
        .values(values)
        .metadata(listResultMetadata)
        .nextStart(nextStart)
        .havingMore(pagedList.hasNext())
        .totalCount(pagedList.getTotalCount())
        .totalPageCount(pagedList.getTotalPageCount())
        .pageSize(pagedList.getPageSize())
        .build();
  }

  @Nonnull
  private static ExtraInfo toExtraInfo(@Nonnull EbeanMetadataAspect aspect) {
    final ExtraInfo extraInfo = new ExtraInfo();
    extraInfo.setVersion(aspect.getKey().getVersion());
    extraInfo.setAudit(makeAuditStamp(aspect));
    try {
      extraInfo.setUrn(Urn.createFromString(aspect.getKey().getUrn()));
    } catch (URISyntaxException e) {
      throw new ModelConversionException(e.getMessage());
    }

    return extraInfo;
  }

  @Nonnull
  private static AuditStamp makeAuditStamp(@Nonnull EbeanMetadataAspect aspect) {
    final AuditStamp auditStamp = new AuditStamp();
    auditStamp.setTime(aspect.getCreatedOn().getTime());

    try {
      auditStamp.setActor(new Urn(aspect.getCreatedBy()));
      if (aspect.getCreatedFor() != null) {
        auditStamp.setImpersonator(new Urn(aspect.getCreatedFor()));
      }
    } catch (URISyntaxException e) {
      throw new RuntimeException(e);
    }
    return auditStamp;
  }

  @Nonnull
  private ListResultMetadata makeListResultMetadata(@Nonnull List<ExtraInfo> extraInfos) {
    final ListResultMetadata listResultMetadata = new ListResultMetadata();
    listResultMetadata.setExtraInfos(new ExtraInfoArray(extraInfos));
    return listResultMetadata;
  }

  @Override
  public long newNumericId(@Nonnull String namespace, int maxTransactionRetry) {
    return runInTransactionWithRetry(() -> {
      final Optional<EbeanMetadataId> result = _server.find(EbeanMetadataId.class)
          .where()
          .eq(EbeanMetadataId.NAMESPACE_COLUMN, namespace)
          .orderBy()
          .desc(EbeanMetadataId.ID_COLUMN)
          .setMaxRows(1)
          .findOneOrEmpty();

      EbeanMetadataId id = result.orElse(new EbeanMetadataId(namespace, 0));
      id.setId(id.getId() + 1);
      _server.insert(id);
      return id;
    }, maxTransactionRetry).getId();
  }

  @Nonnull
  static GMAIndexPair getGMAIndexPair(@Nonnull IndexCriterion criterion) {
    final IndexValue indexValue = criterion.getPathParams().getValue();
    final Object object;
    if (indexValue.isBoolean()) {
      object = indexValue.getBoolean().toString();
      return new GMAIndexPair(EbeanMetadataIndex.STRING_COLUMN, object);
    } else if (indexValue.isDouble()) {
      object = indexValue.getDouble();
      return new GMAIndexPair(EbeanMetadataIndex.DOUBLE_COLUMN, object);
    } else if (indexValue.isFloat()) {
      object = (indexValue.getFloat()).doubleValue();
      return new GMAIndexPair(EbeanMetadataIndex.DOUBLE_COLUMN, object);
    } else if (indexValue.isInt()) {
      object = Long.valueOf(indexValue.getInt());
      return new GMAIndexPair(EbeanMetadataIndex.LONG_COLUMN, object);
    } else if (indexValue.isLong()) {
      object = indexValue.getLong();
      return new GMAIndexPair(EbeanMetadataIndex.LONG_COLUMN, object);
    } else if (indexValue.isString()) {
      object = getValueFromIndexCriterion(criterion);
      return new GMAIndexPair(EbeanMetadataIndex.STRING_COLUMN, object);
    } else {
      throw new IllegalArgumentException("Invalid index value " + indexValue);
    }
  }

  static String getValueFromIndexCriterion(@Nonnull IndexCriterion criterion) {
    final IndexValue indexValue = criterion.getPathParams().getValue();
    if (criterion.getPathParams().getCondition().equals(Condition.START_WITH)) {
      return indexValue.getString() + "%";
    }
    return indexValue.getString();
  }

  /**
   * Sets the values of parameters in metadata index query based on its position, values obtained from
   * {@link IndexCriterionArray} and last urn. Also sets the LIMIT of SQL query using the page size input.
   *
   * @param indexCriterionArray {@link IndexCriterionArray} whose values will be used to set parameters in metadata
   *                                                       index query based on its position
   * @param indexQuery {@link Query} whose ordered parameters need to be set, based on it's position
   * @param lastUrn string representation of the urn whose value is used to set the last urn parameter in index query
   * @param pageSize maximum number of distinct urns to return which is essentially the LIMIT clause of SQL query
   */
  private static void setParameters(@Nonnull IndexCriterionArray indexCriterionArray,
      @Nonnull Query<EbeanMetadataIndex> indexQuery, @Nonnull String lastUrn, int pageSize) {
    indexQuery.setParameter(1, lastUrn);
    int pos = 2;
    for (IndexCriterion criterion : indexCriterionArray) {
      indexQuery.setParameter(pos++, criterion.getAspect());
      if (criterion.getPathParams() != null) {
        indexQuery.setParameter(pos++, criterion.getPathParams().getPath());
        indexQuery.setParameter(pos++, getGMAIndexPair(criterion).value);
      }
    }
    indexQuery.setParameter(pos, pageSize);
  }

  @Nonnull
  private static String getStringForOperator(@Nonnull Condition condition) {
    if (!CONDITION_STRING_MAP.containsKey(condition)) {
      throw new UnsupportedOperationException(
          condition.toString() + " condition is not supported in local secondary index");
    }
    return CONDITION_STRING_MAP.get(condition);
  }

  /**
   * Constructs SQL query that contains positioned parameters (with `?`), based on whether {@link IndexCriterion} of
   * a given condition has field `pathParams`.
   *
   * @param indexCriterionArray {@link IndexCriterionArray} used to construct the SQL query
   * @return String representation of SQL query
   */
  @Nonnull
  private static String constructSQLQuery(@Nonnull IndexCriterionArray indexCriterionArray) {
    String selectClause = "SELECT DISTINCT(t0.urn) FROM metadata_index t0";
    selectClause += IntStream.range(1, indexCriterionArray.size())
        .mapToObj(i -> " INNER JOIN metadata_index " + "t" + i + " ON t0.urn = " + "t" + i + ".urn")
        .collect(Collectors.joining(""));
    final StringBuilder whereClause = new StringBuilder("WHERE t0.urn > ?");
    IntStream.range(0, indexCriterionArray.size()).forEach(i -> {
      final IndexCriterion criterion = indexCriterionArray.get(i);

      whereClause.append(" AND t").append(i).append(".aspect = ?");
      if (criterion.getPathParams() != null) {
        whereClause.append(" AND t")
            .append(i)
            .append(".path = ? AND t")
            .append(i)
            .append(".")
            .append(getGMAIndexPair(criterion).valueType)
            .append(" ")
            .append(getStringForOperator(criterion.getPathParams().getCondition()))
            .append("?");
      }
    });
    final String orderByClause = "ORDER BY urn ASC";
    final String limitClause = "LIMIT ?";
    return String.join(" ", selectClause, whereClause, orderByClause, limitClause);
  }

  void addEntityTypeFilter(@Nonnull IndexFilter indexFilter) {
    if (indexFilter.getCriteria().stream().noneMatch(x -> x.getAspect().equals(_urnClass.getCanonicalName()))) {
      indexFilter.getCriteria().add(new IndexCriterion().setAspect(_urnClass.getCanonicalName()));
    }
  }

  /**
   * Returns list of urns from strongly consistent secondary index that satisfy the given filter conditions.
   *
   * <p>Results are ordered lexicographically by the string representation of the URN.
   *
   * <p>NOTE: Currently this works for upto 10 filter conditions.
   *
   * @param indexFilter {@link IndexFilter} containing filter conditions to be applied
   * @param lastUrn last urn of the previous fetched page. This eliminates the need to use offset which
   *                 is known to slow down performance of MySQL queries. For the first page, this should be set as NULL
   * @param pageSize maximum number of distinct urns to return
   * @return list of urns from strongly consistent secondary index that satisfy the given filter conditions
   */
  @Override
  @Nonnull
  public List<URN> listUrns(@Nonnull IndexFilter indexFilter, @Nullable URN lastUrn, int pageSize) {
    if (!isLocalSecondaryIndexEnabled()) {
      throw new UnsupportedOperationException("Local secondary index isn't supported");
    }
    final IndexCriterionArray indexCriterionArray = indexFilter.getCriteria();
    if (indexCriterionArray.isEmpty()) {
      throw new UnsupportedOperationException("Empty Index Filter is not supported by EbeanLocalDAO");
    }
    if (indexCriterionArray.size() > 10) {
      throw new UnsupportedOperationException(
          "Currently more than 10 filter conditions is not supported by EbeanLocalDAO");
    }

    addEntityTypeFilter(indexFilter);

    final Query<EbeanMetadataIndex> query =
        _server.findNative(EbeanMetadataIndex.class, constructSQLQuery(indexCriterionArray))
            .setTimeout(INDEX_QUERY_TIMEOUT_IN_SEC);
    setParameters(indexCriterionArray, query, lastUrn == null ? "" : lastUrn.toString(), pageSize);

    final List<EbeanMetadataIndex> pagedList = query.findList();

    return pagedList.stream().map(entry -> getUrn(entry.getUrn())).collect(Collectors.toList());
  }
}