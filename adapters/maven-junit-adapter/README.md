# Maven JUnit Adapter

The generic Adapter executes one independently runnable Maven/JUnit target UT. The UT owns its input
setup and writes normal console output. The Agent captures changed top-level JSON files from the
absolute `resultJsonDirectory` installed from `config/agent-settings.json`.

No Agent configuration file is created in the target algorithm repository. If no JSON is captured,
the run reports that fact without declaring the configured directory wrong and without scanning other
directories.
