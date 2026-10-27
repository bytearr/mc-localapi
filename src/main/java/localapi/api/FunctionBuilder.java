package localapi.api;

/** Declares one function. Nothing is public until the user enables it and picks (or accepts) a path. */
public interface FunctionBuilder {
    FunctionBuilder title(String title);

    FunctionBuilder description(String description);

    /** Proposal only. The user may change it. Must start with "/". */
    FunctionBuilder suggestedPath(String path);

    /** "GET" or "POST". Default GET. */
    FunctionBuilder method(String method);

    FunctionBuilder param(String name, ParamType type, boolean required, String description);

    /** Optional parameter with a default value, given as text ("5", "true", ...). */
    FunctionBuilder param(String name, ParamType type, String defaultValue, String description);

    /** One-shot call that needs game state: runs on the game thread through the queue and its timeout. */
    FunctionBuilder once(OnceHandler handler);

    /** One-shot call that does not touch the game: runs on a worker thread, not the 4 ms frame budget. */
    FunctionBuilder onceOffGameThread(OnceHandler handler);

    FunctionBuilder stream(StreamHandler handler);

    /** Finishes the declaration. Throws IllegalStateException if it is incomplete. */
    void register();
}
