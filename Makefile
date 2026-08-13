NPM ?= npm
INSTALL_ARGS ?=

.PHONY: install
install:
	$(NPM) run install:macos -- $(INSTALL_ARGS)
