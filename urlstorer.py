#! /usr/bin/python

import sqlite3
import sys
import re

def processLine(input):
    print(input)
    urls = re.findall('''<a href="(https?://[\S]*)"''', input)
    print(len(urls))
    for url in urls:
        data = (url,)
        c.execute('''INSERT INTO urls VALUES (date('now'),?);''',data)
        print("Writing to database: " + url[0:-1])
        conn.commit();

conn = sqlite3.connect('urls.db')
c = conn.cursor()

c.execute('''CREATE TABLE IF NOT EXISTS urls (time text, url text);''')

try:
    print("Reading from stdin")
    input = ''
    while True:
        input += sys.stdin.read(1)
        if input.endswith('\n'):
            processLine(input)
            input = ''
except KeyboardInterrupt:
    conn.close();
    print("losing down.") # C provided by ^C
except RuntimeError as e:
    conn.close()
    print(e)

