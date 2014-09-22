
# redirector

This is an example HTTP redirector for a client.

The client supplies a URL of the form http://service-name.herokuapp.com/BRAND/COUNTRY/resource.jpg

The server re-writes the URL based on cached data in REDIS or, if not in REDIS, from MongoDB

The new URL is returned via HTTP status code 302 (Found) with the Location header filled out to the new URL

Any non-matches are returned with a status code 404 (Not Found)

