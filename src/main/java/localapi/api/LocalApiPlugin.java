package localapi.api;

/**
 * Entry class of a plugin jar (named in localapi.plugin.json as "entrypoint").
 * Needs a public no-argument constructor. register() runs once when the plugin is loaded.
 * Declare functions and options here. Functions run on the core's threads; a plugin that needs its own worker
 * (e.g. captions) may start a daemon thread here, but must stop it itself and never block register().
 */
public interface LocalApiPlugin {
    void register(PluginContext ctx) throws Exception;
}
