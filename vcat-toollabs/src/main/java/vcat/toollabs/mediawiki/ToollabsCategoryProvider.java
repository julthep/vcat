package vcat.toollabs.mediawiki;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import vcat.VCatException;
import vcat.mediawiki.ApiException;
import vcat.mediawiki.ICategoryProvider;
import vcat.mediawiki.IMetadataProvider;
import vcat.mediawiki.Metadata;
import vcat.toollabs.ToollabsWiki;
import vcat.util.CollectionHelper;

public class ToollabsCategoryProvider implements ICategoryProvider<ToollabsWiki> {

	private final ToollabsConnectionBuilder connectionBuilder;

	private final IMetadataProvider metadataProvider;

	public ToollabsCategoryProvider(final ToollabsConnectionBuilder connectionBuilder,
			final IMetadataProvider metadataProvider) {
		this.connectionBuilder = connectionBuilder;
		this.metadataProvider = metadataProvider;
	}

	private static String preparedStatementInArguments(int numberOfArguments) {
		StringBuilder sb = new StringBuilder(numberOfArguments * 2 + 1);
		sb.append('(');
		if (numberOfArguments > 0) {
			sb.append('?');
		}
		for (int i = 1; i < numberOfArguments; i++) {
			sb.append(',');
			sb.append('?');
		}
		sb.append(')');
		return sb.toString();
	}

	@Override
	public Map<String, Collection<String>> requestCategories(ToollabsWiki wiki, Collection<String> fullTitles,
			boolean showhidden) throws ApiException {

		final String dbname = wiki.getName();
		Metadata metadata;
		try {
			metadata = metadataProvider.requestMetadata(wiki);
		} catch (ApiException e) {
			throw new ApiException("Could not retrieve metadata for wiki", e);
		}

		Connection connection;
		try {
			connection = this.connectionBuilder.buildConnection(dbname);
		} catch (VCatException e) {
			throw new ApiException("Could not get connection for requestCategories", e);
		}

		// Get namespaces from full titles and store the truncated titles and full titles by namespace
		final HashMap<Integer, ArrayList<String>> titlesByNamespace = new HashMap<Integer, ArrayList<String>>();
		for (String fullTitle : fullTitles) {
			final String title = metadata.titleWithoutNamespace(fullTitle);
			final int namespace = metadata.namespaceFromTitle(fullTitle);
			ArrayList<String> list = titlesByNamespace.get(namespace);
			if (list == null) {
				list = new ArrayList<String>(1);
				titlesByNamespace.put(namespace, list);
			}
			list.add(title);
		}

		final HashMap<String, Collection<String>> result = new HashMap<String, Collection<String>>(fullTitles.size());

		for (int namespace : titlesByNamespace.keySet()) {
			final ArrayList<String> allTitles = titlesByNamespace.get(namespace);

			for (final Collection<String> titles : CollectionHelper.splitCollectionInParts(allTitles, 100)) {

				try {
					StringBuilder sql = new StringBuilder(
							"SELECT page_title, cl_to FROM categorylinks INNER JOIN page ON page_id=cl_from"
									+ " WHERE page_namespace=? AND page_title IN "
									+ preparedStatementInArguments(titles.size()));
					if (!showhidden) {
						sql.append(" AND NOT EXISTS (SELECT * FROM page_props WHERE pp_page=page_id AND pp_propname='hiddencat');");
					}
					final PreparedStatement statement = connection.prepareStatement(sql.toString());
					statement.setInt(1, namespace);
					int i = 2;
					for (String title : titles) {
						statement.setString(i, title);
						i++;
					}
					ResultSet rs = statement.executeQuery();
					while (rs.next()) {
						// We need the page_title because we get results for different pages
						final String page_title = rs.getString("page_title");
						final String cl_to = rs.getString("cl_to");
						// This is the full title we got the categories for
						final String fullTitle = metadata.fullTitle(page_title, namespace);
						// This is the full title of the categories themselves
						final String categoryFullTitle = metadata.fullTitle(cl_to, Metadata.NS_CATEGORY);
						// Put title in results at correct fullTitle key
						Collection<String> categoryFullTitles = result.get(fullTitle);
						if (categoryFullTitles == null) {
							categoryFullTitles = new ArrayList<String>(1);
							result.put(fullTitle, categoryFullTitles);
						}
						categoryFullTitles.add(categoryFullTitle);
					}
					rs.close();
				} catch (SQLException e) {
					throw new ApiException("Error reading categories from Tool Labs database '" + dbname + '\'', e);
				}
			}
		}

		try {
			connection.close();
		} catch (SQLException e) {
			// ignore
		}

		return result;

	}

	@Override
	public List<String> requestCategorymembers(ToollabsWiki wiki, String fullTitle) throws ApiException {

		// TODO: Does not work yet.

		final String dbname = wiki.getName();
		Metadata metadata;
		try {
			metadata = this.metadataProvider.requestMetadata(wiki);
		} catch (ApiException e) {
			throw new ApiException("Could not retrieve metadata for wiki", e);
		}

		Connection connection;
		try {
			connection = this.connectionBuilder.buildConnection(dbname);
		} catch (VCatException e) {
			throw new ApiException("Could not get connection for requestCategories", e);
		}

		final ArrayList<String> result = new ArrayList<String>();

		final String title = metadata.titleWithoutNamespace(fullTitle);

		try {
			final PreparedStatement statement = connection
					.prepareStatement("SELECT page_title, page_namespace FROM page INNER JOIN categorylinks ON cl_from=page_id WHERE cl_to=?");
			statement.setString(1, title);
			ResultSet rs = statement.executeQuery();
			while (rs.next()) {
				final String page_title = rs.getString("page_title");
				final int page_namespace = rs.getInt("page_namespace");
				result.add(metadata.fullTitle(page_title, page_namespace));
			}
			rs.close();
		} catch (SQLException e) {
			throw new ApiException("Error reading category members from Tool Labs database '" + dbname + '\'', e);
		}

		try {
			connection.close();
		} catch (SQLException e) {
			// ignore
		}

		return result;
	}
}
