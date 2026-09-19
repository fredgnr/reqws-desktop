"""Print an allowlisted, credential-free GitHub Release job context."""

import json
import os
import platform


def release_context(environment):
    names = (
        'GITHUB_RUN_ID', 'GITHUB_RUN_ATTEMPT', 'GITHUB_JOB', 'GITHUB_EVENT_NAME',
        'GITHUB_REF', 'GITHUB_SHA', 'RUNNER_OS', 'RUNNER_ARCH',
    )
    return {name: environment[name] for name in names if name in environment}


if __name__ == '__main__':
    # JSON keeps controls/newlines in public ref names from becoming log commands.
    context = release_context(os.environ)
    context.update(system=platform.system(), system_release=platform.release(), architecture=platform.machine())
    print('[release][context] ' + json.dumps(context, sort_keys=True), flush=True)
