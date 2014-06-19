package vcat.toollabs.webapp;

import java.io.File;
import java.util.Map;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;

import org.apache.commons.codec.digest.DigestUtils;

import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

import com.mchange.v2.c3p0.ComboPooledDataSource;

import vcat.VCatException;
import vcat.cache.IApiCache;
import vcat.cache.IMetadataCache;
import vcat.graphviz.GraphvizExternal;
import vcat.graphviz.QueuedGraphviz;
import vcat.mediawiki.CachedApiClient;
import vcat.mediawiki.CachedMetadataProvider;
import vcat.mediawiki.IMetadataProvider;
import vcat.redis.cache.ApiRedisCache;
import vcat.redis.cache.MetadataRedisCache;
import vcat.renderer.CachedVCatRenderer;
import vcat.renderer.IVCatRenderer;
import vcat.renderer.QueuedVCatRenderer;
import vcat.renderer.RenderedFileInfo;
import vcat.toollabs.base.AllParamsToollabs;
import vcat.toollabs.base.MyCnfConfig;
import vcat.toollabs.base.ToollabsWiki;
import vcat.toollabs.base.ToollabsWikiProvider;

public class ToollabsVCatServlet extends AbstractVCatToollabsServlet {

	private static final long serialVersionUID = -7294276687476708402L;

	/** Directory with Graphviz binaries (dot, fdp). */
	private static final String GRAPHVIZ_DIR = "/usr/bin";

	/** Maximum number of concurrent processes running Graphviz (0=unlimited). */
	private static final int GRAPHVIZ_PROCESSES = 1;

	private static final String HOME_CACHE_DIR = "cache";

	private static final String HOME_TEMP_DIR = "temp";

	/** JDBC URL for MySQL/MariaDB access to wiki table. */
	private static final String JDBC_URL = "jdbc:mysql://enwiki.labsdb:3306/meta_p";

	/** Purge caches after (seconds). */
	private static final int PURGE = 600;

	/** Purge metadata after (seconds). */
	private static final int PURGE_METADATA = 86400;

	/** Redis server hostname. */
	private static final String REDIS_HOSTNAME = "localhost";

	/** Redis server port. */
	private static final int REDIS_PORT = 6379;

	private IVCatRenderer<ToollabsWiki> vCatRenderer;

	private IMetadataProvider metadataProvider;

	private ToollabsWikiProvider toollabsWikiProvider;

	@Override
	public String getServletInfo() {
		return Messages.getString("ToollabsVCatServlet.ServletInfo");
	}

	@Override
	public void init() throws ServletException {

		MyCnfConfig configMyCnf = new MyCnfConfig();

		// Pool for database connections
		final ComboPooledDataSource cpds = new ComboPooledDataSource();
		cpds.setJdbcUrl(JDBC_URL);
		cpds.setUser(configMyCnf.user);
		cpds.setPassword(configMyCnf.password);
		// Stay small and close connections quickly - this is only used for metadata for now, so it's not used much
		cpds.setInitialPoolSize(1);
		cpds.setMinPoolSize(0);
		cpds.setMaxPoolSize(10);
		cpds.setAcquireIncrement(1);
		cpds.setMaxIdleTime(600);
		cpds.setMaxConnectionAge(3600);

		// Provider for Tool Labs wiki information
		this.toollabsWikiProvider = new ToollabsWikiProvider(cpds);

		// Use database credentials to create a secret prefix for caches
		final String redisSecret = DigestUtils.md5Hex(configMyCnf.user + ':' + configMyCnf.password);

		final String redisApiCacheKeyPrefix = redisSecret + "-cache-api-";
		final String redisMetadataCacheKeyPrefix = redisSecret + "-cache-metadata-";

		// Conservative configuration for Redis connection pool - check connections as often as possible
		final JedisPoolConfig poolConfig = new JedisPoolConfig();
		poolConfig.setTestOnBorrow(true);
		poolConfig.setTestOnReturn(true);
		poolConfig.setTestWhileIdle(true);
		// Allow some more concurrent connections
		poolConfig.setMaxTotal(16);
		// We expect low traffic most of the time, so don't keep many idle connections open
		poolConfig.setMaxIdle(1);
		// Keep one spare idle connection
		poolConfig.setMinIdle(1);

		// Pool of Redis connections
		final JedisPool jedisPool = new JedisPool(poolConfig, REDIS_HOSTNAME, REDIS_PORT);

		// Use Redis for API and metadata caches
		final IApiCache apiCache = new ApiRedisCache(jedisPool, redisApiCacheKeyPrefix, PURGE);
		final IMetadataCache metadataCache = new MetadataRedisCache(jedisPool, redisMetadataCacheKeyPrefix,
				PURGE_METADATA);

		// Call external executables, but use a QueuedGraphviz to limit number of concurrent processes.
		final QueuedGraphviz graphviz = new QueuedGraphviz(new GraphvizExternal(new File(GRAPHVIZ_DIR)),
				GRAPHVIZ_PROCESSES);

		final CachedApiClient<ToollabsWiki> apiClient = new CachedApiClient<>(apiCache);

		// Metadata provider
		this.metadataProvider = new CachedMetadataProvider(apiClient, metadataCache);

		// Home directory
		final File homeDirectory = new File(System.getProperty("user.home"));

		// For cache of Graphviz files and rendered images, use this directory
		final File cacheDir = new File(homeDirectory, HOME_CACHE_DIR);
		cacheDir.mkdirs();
		// Temporary directory for Graphviz files and rendered images
		final File tempDir = new File(homeDirectory, HOME_TEMP_DIR);
		tempDir.mkdirs();

		// Create renderer
		try {
			this.vCatRenderer = new QueuedVCatRenderer<>(new CachedVCatRenderer<>(graphviz, tempDir, apiClient,
					cacheDir), 10);
		} catch (VCatException e) {
			throw new ServletException(e);
		}
		
	}

	protected Map<String, String[]> parameterMap(final HttpServletRequest req) {
		return req.getParameterMap();
	}

	@Override
	protected RenderedFileInfo renderedFileFromRequest(final HttpServletRequest req) throws ServletException {
		try {
			return this.vCatRenderer.render(new AllParamsToollabs(this.parameterMap(req), this.getHttpRequestURI(req),
					this.metadataProvider, this.toollabsWikiProvider));
		} catch (VCatException e) {
			throw new ServletException(e);
		}
	}

}