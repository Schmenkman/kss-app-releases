# Host-Unit-Tests für Parser und Protokoll (ohne ESP-IDF):  make -C firmware/test
CC ?= cc
CFLAGS ?= -std=c11 -O1 -g -Wall -Wextra -Werror -fsanitize=address,undefined -fno-omit-frame-pointer
SRC = test_main.c ../main/apf_parse.c ../main/apf_proto.c

test: run_tests
	./run_tests

run_tests: $(SRC) ../main/apf_parse.h ../main/apf_proto.h
	$(CC) $(CFLAGS) -I../main -o $@ $(SRC)

clean:
	rm -f run_tests

.PHONY: test clean
