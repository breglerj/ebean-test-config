package io.ebean.test.config.platform;

import io.ebean.config.ServerConfig;
import io.ebean.docker.container.ContainerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import static java.util.concurrent.CompletableFuture.allOf;
import static java.util.concurrent.CompletableFuture.runAsync;

public class PlatformAutoConfig {

  private static final Logger log = LoggerFactory.getLogger(PlatformAutoConfig.class);

  /**
   * Known platforms we can setup locally or via docker container.
   */
  private static Map<String, PlatformSetup> KNOWN_PLATFORMS = new HashMap<>();

  static {
    KNOWN_PLATFORMS.put("h2", new H2Setup());
    KNOWN_PLATFORMS.put("sqlite", new SqliteSetup());
    KNOWN_PLATFORMS.put("postgres", new PostgresSetup());
    KNOWN_PLATFORMS.put("postgis", new PostgisSetup());
    KNOWN_PLATFORMS.put("mysql", new MySqlSetup());
    KNOWN_PLATFORMS.put("sqlserver", new SqlServerSetup());
    KNOWN_PLATFORMS.put("oracle", new OracleSetup());
    KNOWN_PLATFORMS.put("hana", new HanaSetup());
  }

  private final ServerConfig serverConfig;

  private final Properties properties;

  private String db;

  private String platform;

  private PlatformSetup platformSetup;

  private String databaseName;

  public PlatformAutoConfig(String db, ServerConfig serverConfig) {
    this.db = db;
    this.serverConfig = serverConfig;
    this.properties = serverConfig.getProperties();
  }

  /**
   * Configure the DataSource for the extra database.
   */
  public void configExtraDataSource() {
    determineTestPlatform();
    if (isKnownPlatform()) {
      databaseName = serverConfig.getName();
      db = serverConfig.getName();

      Config config = new Config(db, platform, databaseName, serverConfig);
      platformSetup.setupExtraDbDataSource(config);
      log.debug("configured dataSource for extraDb name:{} url:{}", db, serverConfig.getDataSourceConfig().getUrl());
    }
  }

  /**
   * Run setting up for testing.
   */
  public void run() {
    determineTestPlatform();
    if (isKnownPlatform()) {
      readDbName();
      setupForTesting();
    }
  }

  private void setupForTesting() {

    // start containers in parallel
    allOf(runAsync(this::setupElasticSearch), runAsync(this::setupDatabase)).join();
  }

  private void setupElasticSearch() {
    new ElasticSearchSetup(properties).run();
  }

  private void setupDatabase() {
    Config config = new Config(db, platform, databaseName, serverConfig);
    Properties dockerProperties = platformSetup.setup(config);
    if (!dockerProperties.isEmpty()) {
      if (isDebug()) {
        log.info("Docker properties: {}", dockerProperties);
      } else {
        log.debug("Docker properties: {}", dockerProperties);
      }
      // start the docker container with appropriate configuration
      new ContainerFactory(dockerProperties, config.getDockerPlatform()).startContainers();
    }
  }

  private boolean isDebug() {
    String val = properties.getProperty("ebean.test.debug");
    return (val != null && val.equalsIgnoreCase("true"));
  }

  private void readDbName() {
    databaseName = properties.getProperty("ebean.test.dbName");
    if (databaseName == null) {
      if (inMemoryDb()) {
        databaseName = "test_db";
      } else {
        throw new IllegalStateException("ebean.test.dbName is not set but required for testing configuration with platform " + platform);
      }
    }
  }

  private boolean inMemoryDb() {
    return platformSetup.isLocal();
  }

  /**
   * Return true if we match a known platform and know how to set it up for testing (via docker usually).
   */
  private boolean isKnownPlatform() {
    if (platform == null) {
      return false;
    }
    this.platformSetup = KNOWN_PLATFORMS.get(platform);
    if (platformSetup == null) {
      log.warn("unknown platform {} - skipping platform setup", platform);
    }
    return platformSetup != null;
  }

  /**
   * Determine the platform we are going to use to run testing.
   */
  private void determineTestPlatform() {

    String testPlatform = properties.getProperty("ebean.test.platform");
    if (testPlatform != null && !testPlatform.isEmpty()) {
      if (db == null) {
        platform = testPlatform.trim();
        db = "db";
      } else {
        // using command line system property to test alternate platform
        // and we expect db to match a platform name
        platform = db;
      }
    }
  }
}
