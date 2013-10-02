package vcat.mediawiki;

import java.util.Collection;
import java.util.List;
import java.util.Map;

public interface ICategoryProvider<W extends IWiki> {

	/**
	 * @param fullTitles
	 *            Full titles (including namespace) of pages.
	 * @return Set of strings with the full titles (with namespace) of categories the page with the supplied full title
	 *         is in.
	 * @throws ApiException
	 *             If there are any errors accessing the MediaWiki API.
	 */
	public abstract Map<String, Collection<String>> requestCategories(W wiki, Collection<String> fullTitles,
			boolean showhidden) throws ApiException;

	public abstract List<String> requestCategorymembers(W wiki, String fullTitle) throws ApiException;

}