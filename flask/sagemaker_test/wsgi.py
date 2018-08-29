"""WSGI Entrypoint."""
from sagemaker_test.app import application

if __name__ == "__main__":
    application.run()
