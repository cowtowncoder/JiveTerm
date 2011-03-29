# -*-makefile-*-
# Makefile
#
# Created      : 06-Jan-99, TSa
# Last modified: - "" -
#

#JAVAC=		javac
JAVAC=		jikes
#JAVAC_FLAGS=	-d .
JAVAC_FLAGS=	-classpath .:/usr/lib/java3/jre/lib/rt.jar
JAVAH=		javah

JCC =		../bin/jcc

BASIC=		jiveterm/CharAttrs.class \
		jiveterm/Display.class \
		jiveterm/FontLoader.class\
		jiveterm/JiveConnection.class \
		jiveterm/JiveTerm.class\
		jiveterm/MessageBox.class \
		jiveterm/PlatformSpecific.class \
		jiveterm/TelnetConnection.class \
		jiveterm/Terminal.class

SSH=		jiveterm/JiveSSHClient.class \
		jiveterm/SSHConnection.class

all:		$(BASIC) $(SSH)
		wc $(JAVAS)

jiveterm:	$(BASIC)

%.class: %.java
	$(JAVAC) $(JAVAC_FLAGS) $<

.PHONY : all

clean:
	rm -f jiveterm/*.class
	rm -f *~
	rm -f jiveterm/*~
	rm -f core
