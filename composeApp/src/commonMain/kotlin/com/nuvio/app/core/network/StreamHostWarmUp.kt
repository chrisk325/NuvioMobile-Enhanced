package com.nuvio.app.core.network

/**
 * Looks up the servers of a stream list's links in the background, so the stream picked from it
 * does not wait for that lookup. Only DNS is asked: nothing is sent to the servers themselves.
 */
internal expect fun warmUpStreamHosts(urls: List<String>)
