"""Test flask app."""

from flask import Flask
application = Flask(__name__)


@application.route("/")
def hello():
    """test function"""
    return "hello world!"

if __name__ == "__main__":
    application.run(host='0.0.0.0')
