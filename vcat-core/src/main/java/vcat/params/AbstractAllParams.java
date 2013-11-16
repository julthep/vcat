package vcat.params;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;

import vcat.VCatException;
import vcat.mediawiki.ApiException;
import vcat.mediawiki.IMetadataProvider;
import vcat.mediawiki.IWiki;
import vcat.mediawiki.Metadata;

/**
 * A bundle of {@link CombinedParams} plus temporary objects needed to build the category graph. Can only be constructed
 * from a set of request parameters, such as these passed in a ServletRequest; this is parsed, and parameters etc. are
 * created as necessary.
 * 
 * @author Peter Schlömer
 */
public abstract class AbstractAllParams<W extends IWiki> {

	private static final int MAX_LIMIT = 500;

	private final CombinedParams<W> combinedParams = new CombinedParams<W>();

	private Metadata metadata;

	protected void init(final Map<String, String[]> requestParams, final IMetadataProvider metadataProvider)
			throws VCatException {

		// Get a copy of the parameters we can modify
		HashMap<String, String[]> params = new HashMap<String, String[]>(requestParams);

		String wikiString = getAndRemove(params, "wiki");
		if (wikiString == null) {
			throw new VCatException("Parameter 'wiki' missing.");
		}
		W wiki = initWiki(wikiString);
		this.getVCat().setWiki(wiki);

		try {
			this.metadata = metadataProvider.requestMetadata(wiki);
		} catch (ApiException e) {
			throw new VCatException("Error retrieving metadata", e);
		}

		//
		// Parameters for graphviz itself
		//

		String formatString = getAndRemove(params, "format");
		String algorithmString = getAndRemove(params, "algorithm");

		OutputFormat format = OutputFormat.PNG;
		if (formatString != null) {
			format = OutputFormat.valueOfIgnoreCase(formatString);
			if (format == null) {
				throw new VCatException("Unknown value for parameter 'format': '" + formatString + "'");
			}
		}

		Algorithm algorithm = Algorithm.DOT;
		if (algorithmString != null) {
			algorithm = Algorithm.valueOfIgnoreCase(algorithmString);
			if (algorithm == null) {
				throw new VCatException("Unknown value for parameter 'algorithm': '" + algorithmString + "'");
			}
		}

		this.getGraphviz().setOutputFormat(format);
		this.getGraphviz().setAlgorithm(algorithm);

		//
		// Parameter for vCat creation
		//

		String[] categoryStrings = getAndRemoveMulti(params, "category");
		String[] titles = getAndRemoveMulti(params, "title");
		String ns = getAndRemove(params, "ns");
		String depthString = getAndRemove(params, "depth");
		String limitString = getAndRemove(params, "limit");
		String showhiddenString = getAndRemove(params, "showhidden");
		String relationString = getAndRemove(params, "rel");

		// 'category'
		if (categoryStrings != null) {
			// If set, convert it to 'title' and 'ns' parameters; the other parameter may not also be used
			if (titles != null || ns != null) {
				throw new VCatException("Parameters 'title' and 'ns' may not be used together with 'category'.");
			}
			titles = categoryStrings;
			ns = Integer.toString(Metadata.NS_CATEGORY);
		}

		// 'title'
		if (titles == null) {
			throw new VCatException("Parameter 'title' or 'category' missing.");
		}
		for (int i = 0; i < titles.length; i++) {
			titles[i] = unescapeMediawikiTitle(titles[i]);
		}

		// 'ns' (namespace)
		int[] namespaces = new int[titles.length];
		if (ns == null) {
			// Automatically determine namespace from titles. Checks if it starts with a namespace; if not, the default
			// (0) is used.
			for (int i = 0; i < titles.length; i++) {
				namespaces[i] = this.metadata.namespaceFromTitle(titles[i]);
				titles[i] = this.metadata.titleWithoutNamespace(titles[i]);
			}
		} else {
			// Try to parse 'ns' as an integer
			int namespace;
			try {
				namespace = Integer.parseInt(ns);
			} catch (NumberFormatException e) {
				throw new VCatException("Parameter 'ns': '" + ns + "' is not a valid number.", e);
			}
			if (this.metadata.getAllNames(namespace).isEmpty()) {
				throw new VCatException("Parameter 'ns': namespace " + namespace + " does not exist.");
			}
			for (int i = 0; i < titles.length; i++) {
				namespaces[i] = namespace;
			}
		}

		// 'depth'
		Integer depth = null;
		if (depthString != null) {
			try {
				depth = Integer.parseInt(depthString);
			} catch (NumberFormatException e) {
				throw new VCatException("Parameter 'depth': '" + depthString + "' is not a valid number.", e);
			}
			if (depth < 1) {
				throw new VCatException("Parameter 'depth' must be greator than or equal to 1.");
			}
		}

		// 'limit' - default is MAX_LIMIT, unless outputting raw graphviz test, and cannot be set higher
		int maxLimit = (format == OutputFormat.GraphvizRaw) ? Integer.MAX_VALUE : MAX_LIMIT;
		Integer limit = MAX_LIMIT;
		if (limitString != null) {
			try {
				limit = Integer.parseInt(limitString);
			} catch (NumberFormatException e) {
				throw new VCatException("Parameter 'limit': '" + limitString + "' is not a valid number.", e);
			}
			if (limit < 1) {
				throw new VCatException("Parameter 'limit' must be greater than or equal to 1.");
			} else if (limit > maxLimit) {
				throw new VCatException("Parameter 'depth' must be less than or equal to " + maxLimit + ".");
			}
		}

		// 'showhidden'
		boolean showhidden = (showhiddenString != null);

		// 'rel' - relation to use (categories, subcategories)
		Relation relation = Relation.Category;
		if (relationString != null) {
			relation = Relation.valueOfIgnoreCase(relationString);
			switch (relation) {
			case Category:
				// all ok
				break;
			case Subcategory:
				for (int namespace : namespaces) {
					if (namespace != Metadata.NS_CATEGORY) {
						throw new VCatException("Parameter 'rel=" + relationString
								+ "' can only be used for categories");
					}
				}
				break;
			default:
				throw new VCatException("Unknown value for parameter 'rel': '" + relationString + "'");
			}
		}

		// Build TitleNamespaceParams
		ArrayList<TitleNamespaceParam> titleNamespaceList = new ArrayList<TitleNamespaceParam>(titles.length);
		for (int i = 0; i < titles.length; i++) {
			titleNamespaceList.add(new TitleNamespaceParam(titles[i], namespaces[i]));
		}

		// Move values to parameters
		this.getVCat().setTitleNamespaceParams(titleNamespaceList);
		this.getVCat().setDepth(depth);
		this.getVCat().setLimit(limit);
		this.getVCat().setShowhidden(showhidden);
		this.getVCat().setRelation(relation);

		//
		// After all this handling, no parameters should be left
		//

		if (!params.isEmpty()) {
			throw new VCatException("Unknown parameter(s): '" + StringUtils.join(params.keySet(), "', '") + "'");
		}

	}

	protected static String getAndRemove(Map<String, String[]> params, String key) throws VCatException {
		String[] values = params.get(key);
		if (values == null || values.length == 0) {
			return null;
		} else if (values.length == 1) {
			params.remove(key);
			return values[0];
		} else {
			throw new VCatException("Parameter '" + key + "' may only be supplied once");
		}
	}

	protected static String[] getAndRemoveMulti(Map<String, String[]> params, String key) throws VCatException {
		String[] values = params.get(key);
		if (values == null) {
			return null;
		} else if (values.length == 0) {
			params.remove(key);
			return null;
		} else {
			params.remove(key);
			return values;
		}
	}

	/**
	 * Determine Wiki from supplied wiki parameter.
	 * 
	 * @param wikiString
	 * @return
	 * @throws VCatException
	 */
	protected abstract W initWiki(final String wikiString) throws VCatException;

	private static String unescapeMediawikiTitle(String title) {
		if (title == null) {
			return null;
		} else {
			return title.replace('_', ' ');
		}
	}

	public CombinedParams<W> getCombined() {
		return this.combinedParams;
	}

	public GraphvizParams getGraphviz() {
		return this.combinedParams.getGraphviz();
	}

	public Metadata getMetadata() {
		return this.metadata;
	}

	public VCatParams<W> getVCat() {
		return this.combinedParams.getVCat();
	}

	public W getWiki() {
		return this.combinedParams.getVCat().getWiki();
	}

}
